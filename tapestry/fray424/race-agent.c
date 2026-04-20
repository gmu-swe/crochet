/*
 * race-agent.c — force Fray #424 (FrayInternalError in objectWaitDoneImpl).
 *
 * Root cause recap:
 *   When thread T dies the JVM fires notify_all_at_thread_exit(T) natively.
 *   Any thread waiting in Object.wait(T) (i.e. doing T.join()) is notified.
 *   That waiting thread calls Fray's objectWaitDoneImpl hook, which checks:
 *     if (state == Running) pendingOperation must be ThreadResumeOperation
 *   Fray's scheduler sets state=Running in scheduleNextOperation (line 1301)
 *   and sets pendingOperation=ThreadResumeOperation in runThread (line 1321),
 *   two separate instructions in the same call chain.  If objectWaitDoneImpl
 *   runs between them the check fails → FrayInternalError.
 *
 * Race timeline (threadCompleted → async executor path):
 *   1. Worker finishes; threadCompleted() submits executor task.
 *   2. Worker thread dies; JVM fires notify_all_at_thread_exit(worker).
 *      Joiner wakes in objectWaitDoneImpl spin-loop:
 *        while (state != Running) { (worker as Object).wait() }
 *      state is NOT yet Running → joiner calls Object.wait(worker) again.
 *   3. Async executor task detects t.isAlive==false, calls
 *      scheduleNextOperationAndCheckDeadlock.
 *   4. scheduleNextOperation:1301 sets state=Running.
 *   5. runThread is called — BREAKPOINT FIRES HERE (location 0).
 *      At this moment: state=Running, pendingOp=ObjectWakeBlocked.
 *      Joiner is still in Object.wait(worker) from step 2.
 *   6. Agent: sleep 1 ms so joiner is definitely in Object.wait.
 *   7. Agent: call objectContext.sendSignalToObject()
 *              → synchronized(worker) { worker.notifyAll() }
 *      Joiner wakes, checks spin-loop: state==Running → exits loop.
 *      Joiner calls objectWaitDoneImpl:
 *        state==Running, pendingOp==ObjectWakeBlocked → FrayInternalError!
 *   8. Agent: sleep 10 ms (race has already been triggered; this just gives
 *      Fray time to record the error before the scheduler continues).
 *
 * Compile:
 *   gcc -shared -fPIC -O2 \
 *       -I${JAVA_HOME}/include -I${JAVA_HOME}/include/linux \
 *       race-agent.c -o librace-agent.so
 */
#include <jvmti.h>
#include <time.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdatomic.h>

static jvmtiEnv   *g_jvmti;
static atomic_int  g_hit_count = 0;

/* ------------------------------------------------------------------ */
/* ClassPrepare: find runThread in RunContext and arm a breakpoint.    */
/* ------------------------------------------------------------------ */
static void JNICALL
cb_ClassPrepare(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jclass klass)
{
    char *sig = NULL;
    (*jvmti)->GetClassSignature(jvmti, klass, &sig, NULL);
    if (!sig) return;

    /*
     * Match org.pastalab.fray.core.RunContext only — not inner classes ($)
     * which share the prefix but compile runThread$default differently.
     */
    int is_run_context = strstr(sig, "RunContext") != NULL
                         && strchr(sig, '$') == NULL;
    (*jvmti)->Deallocate(jvmti, (unsigned char *)sig);
    if (!is_run_context) return;

    jint       count   = 0;
    jmethodID *methods = NULL;
    (*jvmti)->GetClassMethods(jvmti, klass, &count, &methods);

    for (int i = 0; i < count; i++) {
        char *name = NULL, *desc = NULL;
        (*jvmti)->GetMethodName(jvmti, methods[i], &name, &desc, NULL);

        /* runThread(ThreadContext, ThreadContext, ...) — exclude $default which
         * ends in (int, Object)V.                                             */
        int match = name && strcmp(name, "runThread") == 0
                 && desc
                 && strstr(desc, "Ljava/lang/Object;)V") == NULL;

        if (match) {
            jvmtiError err = (*jvmti)->SetBreakpoint(jvmti, methods[i], 0);
            fprintf(stderr, "[race-agent] breakpoint %s on RunContext.runThread%s\n",
                    err == JVMTI_ERROR_NONE ? "SET" : "FAILED", desc ? desc : "");
        }

        if (name) (*jvmti)->Deallocate(jvmti, (unsigned char *)name);
        if (desc) (*jvmti)->Deallocate(jvmti, (unsigned char *)desc);
    }

    if (methods) (*jvmti)->Deallocate(jvmti, (unsigned char *)methods);
}

/* ------------------------------------------------------------------ */
/* Helpers — clear any pending JNI exception and return NULL.         */
/* ------------------------------------------------------------------ */
static void clear_exc(JNIEnv *jni) { (*jni)->ExceptionClear(jni); }

/* ------------------------------------------------------------------ */
/* Breakpoint: fire a spurious notifyAll on the joiner's wait-object. */
/* ------------------------------------------------------------------ */
static void JNICALL
cb_Breakpoint(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread,
              jmethodID method, jlocation location)
{
    int seq = atomic_fetch_add(&g_hit_count, 1);
    fprintf(stderr, "[race-agent] cb_Breakpoint #%d\n", seq);

    /*
     * runThread(currentThread: ThreadContext, nextThread: ThreadContext)
     *   JVM slot 0 = this (RunContext)
     *   JVM slot 1 = currentThread
     *   JVM slot 2 = nextThread
     *
     * At location 0 the breakpoint fires before any bytecode executes but
     * after the JVM has loaded all method parameters into the local-variable
     * table, so GetLocalObject on slot 2 is valid.
     */
    jobject next_ctx = NULL;
    jvmtiError verr = (*jvmti)->GetLocalObject(jvmti, thread, 0, 2, &next_ctx);
    fprintf(stderr, "[race-agent] GetLocalObject slot2: err=%d next_ctx=%p\n",
            (int)verr, (void*)next_ctx);
    if (verr != JVMTI_ERROR_NONE || !next_ctx) return;

    /* nextThread.getPendingOperation() */
    jclass  tc_class   = (*jni)->GetObjectClass(jni, next_ctx);
    jmethodID get_pend = (*jni)->GetMethodID(jni, tc_class, "getPendingOperation",
        "()Lorg/pastalab/fray/core/concurrency/operations/Operation;");
    if (!get_pend) { clear_exc(jni); return; }

    jobject pending_op = (*jni)->CallObjectMethod(jni, next_ctx, get_pend);
    if ((*jni)->ExceptionCheck(jni)) { clear_exc(jni); return; }
    if (!pending_op) { fprintf(stderr, "[race-agent] #%d pendingOp=null\n", seq); return; }

    /* Print the class name of the pending operation for diagnostics. */
    jclass op_cls = (*jni)->GetObjectClass(jni, pending_op);
    jmethodID get_name = (*jni)->GetMethodID(jni, (*jni)->FindClass(jni, "java/lang/Class"),
                                              "getName", "()Ljava/lang/String;");
    if (get_name) {
        jstring name_str = (jstring)(*jni)->CallObjectMethod(jni, op_cls, get_name);
        if ((*jni)->ExceptionCheck(jni)) { clear_exc(jni); }
        else if (name_str) {
            const char *name_c = (*jni)->GetStringUTFChars(jni, name_str, NULL);
            fprintf(stderr, "[race-agent] #%d pendingOp=%s\n", seq,
                    name_c ? name_c : "(null)");
            if (name_c) (*jni)->ReleaseStringUTFChars(jni, name_str, name_c);
        }
    }
    if ((*jni)->ExceptionCheck(jni)) clear_exc(jni);

    /* Is it ObjectWakeBlocked?  Skip if it's a plain LockSupport unpark. */
    jclass owb_class = (*jni)->FindClass(jni,
        "org/pastalab/fray/core/concurrency/operations/ObjectWakeBlocked");
    if (!owb_class) { clear_exc(jni); return; }
    if (!(*jni)->IsInstanceOf(jni, pending_op, owb_class)) return;

    /* pendingOp.getObjectContext() — type: ObjectNotifyContext */
    jclass    owb_cls   = (*jni)->GetObjectClass(jni, pending_op);
    jmethodID get_ctx   = (*jni)->GetMethodID(jni, owb_cls, "getObjectContext",
        "()Lorg/pastalab/fray/core/concurrency/context/ObjectNotifyContext;");
    if (!get_ctx) { clear_exc(jni); return; }

    jobject obj_ctx = (*jni)->CallObjectMethod(jni, pending_op, get_ctx);
    if ((*jni)->ExceptionCheck(jni)) { clear_exc(jni); return; }
    if (!obj_ctx) return;

    /* objectContext.sendSignalToObject()
     *   → synchronized(worker) { worker.notifyAll() }
     *
     * Sleep 1 ms first so the joiner has time to re-enter Object.wait(worker)
     * after having been woken prematurely by notify_all_at_thread_exit.
     */
    struct timespec ts1 = { 0, 1000000 }; /* 1 ms */
    nanosleep(&ts1, NULL);

    jclass    ctx_cls  = (*jni)->GetObjectClass(jni, obj_ctx);
    jmethodID send_sig = (*jni)->GetMethodID(jni, ctx_cls, "sendSignalToObject", "()V");
    if (!send_sig) { clear_exc(jni); return; }

    fprintf(stderr,
        "[race-agent] ObjectWakeBlocked HIT #%d — calling sendSignalToObject "
        "while pendingOp=ObjectWakeBlocked, state=Running\n", seq);

    (*jni)->CallVoidMethod(jni, obj_ctx, send_sig);
    if ((*jni)->ExceptionCheck(jni)) { clear_exc(jni); }

    /*
     * Sleep 10 ms to keep the race window open: the joiner has been woken and
     * will call objectWaitDoneImpl.  While we sleep here (still inside runThread
     * before line 1321), pendingOp is still ObjectWakeBlocked.  The joiner will
     * see state==Running but pendingOp==ObjectWakeBlocked → FrayInternalError.
     */
    struct timespec ts2 = { 0, 10000000 }; /* 10 ms */
    nanosleep(&ts2, NULL);
}

/* ------------------------------------------------------------------ */
/* Agent bootstrap.                                                    */
/* ------------------------------------------------------------------ */
JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *vm, char *options, void *reserved)
{
    (*vm)->GetEnv(vm, (void **)&g_jvmti, JVMTI_VERSION_1_0);

    jvmtiCapabilities caps = {0};
    caps.can_generate_breakpoint_events = 1;
    caps.can_access_local_variables     = 1;   /* needed for GetLocalObject */
    (*g_jvmti)->AddCapabilities(g_jvmti, &caps);

    jvmtiEventCallbacks cb = {0};
    cb.ClassPrepare = cb_ClassPrepare;
    cb.Breakpoint   = cb_Breakpoint;
    (*g_jvmti)->SetEventCallbacks(g_jvmti, &cb, sizeof(cb));
    (*g_jvmti)->SetEventNotificationMode(g_jvmti, JVMTI_ENABLE,
                                          JVMTI_EVENT_CLASS_PREPARE, NULL);
    (*g_jvmti)->SetEventNotificationMode(g_jvmti, JVMTI_ENABLE,
                                          JVMTI_EVENT_BREAKPOINT,    NULL);

    fprintf(stderr,
        "[race-agent] loaded — will force ObjectWakeBlocked race on "
        "RunContext.runThread entry\n");
    return 0;
}
