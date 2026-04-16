// Minimal JVMTI agent for measuring SINGLE_STEP event overhead.
//
// Exposes:
//   Agent_OnLoad: requests can_generate_single_step_events capability,
//                 registers SingleStep callback.
//   SingleStep callback: atomic-increments an event counter. Nothing else.
//   Exported JNI functions (looked up by the JVM via System.loadLibrary):
//     Java_Bench_enableSingleStep(JNIEnv*, jclass, jobject thread)
//     Java_Bench_disableSingleStep(JNIEnv*, jclass, jobject thread)
//     Java_Bench_getCounter(JNIEnv*, jclass)
//     Java_Bench_resetCounter(JNIEnv*, jclass)

#include <jvmti.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <atomic>

static jvmtiEnv* g_jvmti = nullptr;
static std::atomic<long long> g_event_count{0};

extern "C" {

static void JNICALL
SingleStepCallback(jvmtiEnv* jvmti_env,
                   JNIEnv* jni_env,
                   jthread thread,
                   jmethodID method,
                   jlocation location) {
    // Keep this as tight as possible: we only care about the per-event
    // dispatch/return cost, not about anything we could do inside the callback.
    g_event_count.fetch_add(1, std::memory_order_relaxed);
}

JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM* vm, char* options, void* reserved) {
    jvmtiEnv* jvmti = nullptr;
    jint rc = vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2);
    if (rc != JNI_OK || jvmti == nullptr) {
        fprintf(stderr, "[spike-agent] GetEnv failed: %d\n", rc);
        return JNI_ERR;
    }
    g_jvmti = jvmti;

    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_generate_single_step_events = 1;
    jvmtiError err = jvmti->AddCapabilities(&caps);
    if (err != JVMTI_ERROR_NONE) {
        fprintf(stderr, "[spike-agent] AddCapabilities(single_step) failed: %d\n", err);
        return JNI_ERR;
    }

    jvmtiEventCallbacks cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.SingleStep = &SingleStepCallback;
    err = jvmti->SetEventCallbacks(&cbs, sizeof(cbs));
    if (err != JVMTI_ERROR_NONE) {
        fprintf(stderr, "[spike-agent] SetEventCallbacks failed: %d\n", err);
        return JNI_ERR;
    }
    // Deliberately do NOT globally enable SINGLE_STEP here. The benchmark flips
    // it on per-thread via the JNI methods below.
    fprintf(stderr, "[spike-agent] loaded (capability requested, callback installed)\n");
    return JNI_OK;
}

JNIEXPORT void JNICALL
Agent_OnUnload(JavaVM* vm) {
    fprintf(stderr, "[spike-agent] unloaded; events=%lld\n",
            (long long)g_event_count.load());
}

// ---- JNI entry points bound by name to the Bench class. ----

JNIEXPORT void JNICALL
Java_Bench_enableSingleStep(JNIEnv* env, jclass cls, jobject thread) {
    if (g_jvmti == nullptr) return;
    jvmtiError err = g_jvmti->SetEventNotificationMode(
        JVMTI_ENABLE, JVMTI_EVENT_SINGLE_STEP, (jthread)thread);
    if (err != JVMTI_ERROR_NONE) {
        fprintf(stderr, "[spike-agent] enable SINGLE_STEP failed: %d\n", err);
    }
}

JNIEXPORT void JNICALL
Java_Bench_disableSingleStep(JNIEnv* env, jclass cls, jobject thread) {
    if (g_jvmti == nullptr) return;
    jvmtiError err = g_jvmti->SetEventNotificationMode(
        JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, (jthread)thread);
    if (err != JVMTI_ERROR_NONE) {
        fprintf(stderr, "[spike-agent] disable SINGLE_STEP failed: %d\n", err);
    }
}

JNIEXPORT jlong JNICALL
Java_Bench_getCounter(JNIEnv* env, jclass cls) {
    return (jlong)g_event_count.load();
}

JNIEXPORT void JNICALL
Java_Bench_resetCounter(JNIEnv* env, jclass cls) {
    g_event_count.store(0);
}

} // extern "C"
