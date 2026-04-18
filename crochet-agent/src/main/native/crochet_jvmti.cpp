// crochet-jvmti — optional native agent that exposes stack-frame root
// collection to {@link net.jonbell.crochet.runtime.StackRoots}.
//
// Loaded via:
//   -agentpath:/path/to/libcrochet-jvmti.so
//
// On load:
//   1. Acquires JVMTI 1.2.
//   2. Requests capabilities for stack walking + local variable access.
//   3. Marks {@code StackRoots#engaged} via FindClass + GetStaticMethodID +
//      CallStaticVoidMethod once the VM has booted enough that
//      {@code net.jonbell.crochet.runtime.StackRoots} is loadable
//      (deferred to {@code VMInit} event so the bootstrap loader is
//      available).
//
// Exported native:
//   Java_net_jonbell_crochet_runtime_StackRoots_collectAllStackObjects
//      Walks every live thread (optionally excluding the caller). For each
//      frame, enumerates local-variable slots; for each slot whose
//      registered type is an object reference, fetches the value via
//      GetLocalObject. Accumulates all non-null jobjects into a Java
//      {@code Object[]} returned to the caller.
//
//      Slot enumeration uses the method's {@code LocalVariableTable}
//      attribute (when present) to know which slots are reference-typed
//      at which BCI. When the table is absent (synthetic / reduced-debug
//      methods), we fall back to scanning slots 0..maxLocals with
//      GetLocalObject and accepting the slots that don't return
//      JVMTI_ERROR_TYPE_MISMATCH or _INVALID_SLOT — JVMTI will reject
//      non-reference reads cleanly.
//
//      Caller's own thread is excluded by default because its frames
//      include {@code collectAllStackObjects} → {@code StackRoots
//      .checkpointStackRoots} → {@code CheckpointRollbackAgent
//      .checkpointAll}, which would otherwise re-enter and double-walk.

#include <jvmti.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vector>
#include <mutex>

namespace {

jvmtiEnv* g_jvmti = nullptr;

// Guards calls into JVMTI stack-walking functions. JVMTI requires the target
// thread to be at safepoint for GetLocalObject; in practice we use suspend/
// resume, but a single agent-side mutex is the simplest way to prevent
// concurrent collectAllStackObjects calls from racing each other and
// confusing the suspension count tracking.
std::mutex g_walk_mutex;

void check(jvmtiError err, const char* what) {
    if (err != JVMTI_ERROR_NONE) {
        char* name = nullptr;
        if (g_jvmti != nullptr) {
            g_jvmti->GetErrorName(err, &name);
        }
        fprintf(stderr, "[crochet-jvmti] %s failed: %d (%s)\n",
                what, err, name ? name : "?");
        if (name != nullptr) {
            g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
        }
    }
}

// Collect every reference-typed local in `frame` of `thread` into `out`.
// Returns silently on per-frame errors so a single bad method doesn't abort
// the whole walk. `current_bci` is used to filter the LocalVariableTable
// for the slot range that's live right now.
// Returns true iff the frame's declaring class is one of our agent runtime
// classes — those frames hold no user state and would otherwise loop back
// through propagation when collected. Filtering here lets the caller walk
// its own thread (which is necessary: the user's local variables live on
// the calling thread's frames, just above our own).
bool is_agent_frame(jmethodID method) {
    jclass declaring = nullptr;
    if (g_jvmti->GetMethodDeclaringClass(method, &declaring) != JVMTI_ERROR_NONE
            || declaring == nullptr) {
        return false;
    }
    char* sig = nullptr;
    bool result = false;
    if (g_jvmti->GetClassSignature(declaring, &sig, nullptr) == JVMTI_ERROR_NONE
            && sig != nullptr) {
        // Class signatures are "Lpkg/sub/Cls;" — strip the leading 'L'.
        // Filter out our entire net.jonbell.crochet.* hierarchy: runtime,
        // transform, agent, instrument.
        if (strncmp(sig, "Lnet/jonbell/crochet/", 21) == 0) {
            result = true;
        }
        g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig));
    }
    return result;
}

void collect_frame(jthread thread, jint depth, std::vector<jobject>& out) {
    jmethodID method = nullptr;
    jlocation location = 0;
    jvmtiError err = g_jvmti->GetFrameLocation(thread, depth, &method, &location);
    if (err != JVMTI_ERROR_NONE || method == nullptr) {
        return;
    }

    // Skip native frames — JVMTI rejects GetLocalObject on them.
    jboolean is_native = JNI_FALSE;
    g_jvmti->IsMethodNative(method, &is_native);
    if (is_native) {
        return;
    }

    // Skip frames inside our own runtime — they hold pointers to the very
    // checkpoint/rollback machinery that would loop back if propagated to.
    if (is_agent_frame(method)) {
        return;
    }

    // Try the LocalVariableTable first — gives us exact slot+signature
    // for each local at each BCI. The compiler may have stripped this
    // (no -g), in which case we fall back to scanning maxLocals.
    jint entry_count = 0;
    jvmtiLocalVariableEntry* entries = nullptr;
    err = g_jvmti->GetLocalVariableTable(method, &entry_count, &entries);
    if (err == JVMTI_ERROR_NONE && entries != nullptr) {
        for (jint i = 0; i < entry_count; ++i) {
            const jvmtiLocalVariableEntry& e = entries[i];
            // Reference types start with 'L' (objects) or '[' (arrays).
            if (e.signature == nullptr) continue;
            char first = e.signature[0];
            if (first != 'L' && first != '[') continue;
            // Slot is live iff start_location <= current_location < start+length.
            if (location < e.start_location ||
                location >= e.start_location + (jlocation)e.length) {
                continue;
            }
            jobject val = nullptr;
            jvmtiError e2 = g_jvmti->GetLocalObject(thread, depth, e.slot, &val);
            if (e2 == JVMTI_ERROR_NONE && val != nullptr) {
                out.push_back(val);
            }
        }
        // GetLocalVariableTable allocates per-entry name/signature/generic
        // strings. Deallocate each, then the array.
        for (jint i = 0; i < entry_count; ++i) {
            if (entries[i].name != nullptr)
                g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(entries[i].name));
            if (entries[i].signature != nullptr)
                g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(entries[i].signature));
            if (entries[i].generic_signature != nullptr)
                g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(entries[i].generic_signature));
        }
        g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(entries));
        return;
    }

    // Fallback: scan slots 0..maxLocals. GetMaxLocals tells us the high
    // water mark. Try each slot; non-ref slots get a clean error.
    jint max_locals = 0;
    if (g_jvmti->GetMaxLocals(method, &max_locals) != JVMTI_ERROR_NONE) {
        return;
    }
    for (jint slot = 0; slot < max_locals; ++slot) {
        jobject val = nullptr;
        jvmtiError e2 = g_jvmti->GetLocalObject(thread, depth, slot, &val);
        if (e2 == JVMTI_ERROR_NONE && val != nullptr) {
            out.push_back(val);
        }
    }
}

void collect_thread(jthread thread, std::vector<jobject>& out) {
    jint frame_count = 0;
    if (g_jvmti->GetFrameCount(thread, &frame_count) != JVMTI_ERROR_NONE) {
        return;
    }
    for (jint depth = 0; depth < frame_count; ++depth) {
        collect_frame(thread, depth, out);
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_net_jonbell_crochet_runtime_StackRoots_collectAllStackObjects(
        JNIEnv* env, jclass /*cls*/, jboolean ignore_current) {
    if (g_jvmti == nullptr) return nullptr;

    std::lock_guard<std::mutex> lock(g_walk_mutex);

    // Identify the calling thread so we can skip it (its frames include
    // collectAllStackObjects itself + StackRoots.checkpointStackRoots).
    jthread caller = nullptr;
    g_jvmti->GetCurrentThread(&caller);

    jint thread_count = 0;
    jthread* threads = nullptr;
    jvmtiError err = g_jvmti->GetAllThreads(&thread_count, &threads);
    if (err != JVMTI_ERROR_NONE || threads == nullptr) {
        return nullptr;
    }

    // Collect targets to suspend (everyone except the caller — we MUST
    // never SuspendThread our own thread or we deadlock waiting for our
    // own resume). Note that the caller's frames still need to be walked
    // for stack roots — locals like {@code Holder h} in the user's
    // {@code main()} live there. We just walk them WITHOUT suspending
    // (the calling thread is already at safepoint inside JNI). The
    // {@code is_agent_frame} filter above strips our own frames so we
    // don't loop back into checkpoint propagation.
    //
    // The {@code ignore_current} flag is currently unused but preserved
    // in the signature for API parity with legacy
    // {@code Tagger.checkpointStackRoots(int, boolean)}; setting it
    // {@code true} is meaningless because we'd silently lose all roots
    // held by the calling thread (the dominant case for single-threaded
    // workloads).
    (void)ignore_current;

    std::vector<jthread> targets;
    targets.reserve(thread_count);
    for (jint i = 0; i < thread_count; ++i) {
        if (env->IsSameObject(threads[i], caller)) continue;
        targets.push_back(threads[i]);
    }

    std::vector<jvmtiError> suspend_results(targets.size(), JVMTI_ERROR_NONE);
    if (!targets.empty()) {
        err = g_jvmti->SuspendThreadList(
                static_cast<jint>(targets.size()),
                targets.data(),
                suspend_results.data());
        if (err != JVMTI_ERROR_NONE) {
            // Best-effort: continue with whichever threads suspended.
        }
    }

    std::vector<jobject> out;

    // Walk the caller's own thread first (no suspend needed — we're
    // already at safepoint in JNI). is_agent_frame() filters out our own
    // runtime frames so we don't propagate to the checkpoint machinery.
    if (caller != nullptr) {
        collect_thread(caller, out);
    }

    for (size_t i = 0; i < targets.size(); ++i) {
        if (suspend_results[i] != JVMTI_ERROR_NONE) continue;
        collect_thread(targets[i], out);
    }

    if (!targets.empty()) {
        std::vector<jvmtiError> resume_results(targets.size(), JVMTI_ERROR_NONE);
        g_jvmti->ResumeThreadList(
                static_cast<jint>(targets.size()),
                targets.data(),
                resume_results.data());
    }

    g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(threads));

    // Build the Object[] return value. Use Object as the array element
    // type since the slot values can be any reference type; the Java
    // caller's instanceof CRIJInstrumented check filters appropriately.
    jclass object_cls = env->FindClass("java/lang/Object");
    if (object_cls == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(
            static_cast<jsize>(out.size()), object_cls, nullptr);
    if (result == nullptr) return nullptr;
    for (size_t i = 0; i < out.size(); ++i) {
        env->SetObjectArrayElement(result, static_cast<jsize>(i), out[i]);
        // Local refs from GetLocalObject are owned by us; release each
        // after copying into the array (the array itself holds the strong
        // ref now).
        env->DeleteLocalRef(out[i]);
    }
    return result;
}

void JNICALL VMInitCallback(jvmtiEnv* /*jvmti*/, JNIEnv* env, jthread /*thread*/) {
    // VM is fully booted; safe to load StackRoots and flip its engaged flag.
    jclass cls = env->FindClass("net/jonbell/crochet/runtime/StackRoots");
    if (cls == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        fprintf(stderr, "[crochet-jvmti] could not find StackRoots class\n");
        return;
    }
    jmethodID mark = env->GetStaticMethodID(cls, "markEngaged", "()V");
    if (mark == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        fprintf(stderr, "[crochet-jvmti] could not find markEngaged method\n");
        return;
    }
    env->CallStaticVoidMethod(cls, mark);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return;
    }
    fprintf(stderr, "[crochet-jvmti] engaged\n");
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM* vm, char* /*options*/, void* /*reserved*/) {
    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK) {
        fprintf(stderr, "[crochet-jvmti] GetEnv failed\n");
        return JNI_ERR;
    }
    g_jvmti = jvmti;

    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_access_local_variables = 1;
    caps.can_get_source_file_name = 0;
    caps.can_suspend = 1;
    check(jvmti->AddCapabilities(&caps), "AddCapabilities");

    jvmtiEventCallbacks cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.VMInit = &VMInitCallback;
    check(jvmti->SetEventCallbacks(&cbs, sizeof(cbs)), "SetEventCallbacks");
    check(jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                          JVMTI_EVENT_VM_INIT,
                                          nullptr),
          "SetEventNotificationMode(VMInit)");

    return JNI_OK;
}

extern "C" JNIEXPORT void JNICALL
Agent_OnUnload(JavaVM* /*vm*/) {
    g_jvmti = nullptr;
}
