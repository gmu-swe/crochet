#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <string.h>
#include <criu.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include "jvmti.h"
#include "jni.h"
#pragma GCC diagnostic ignored "-Wwrite-strings"

//#define PRINT_DEBUG true
typedef struct {
	/* JVMTI Environment */
	jvmtiEnv *jvmti;
	JNIEnv * jni;
	jboolean vm_is_started;
	jboolean vmDead;

	JavaVM* jvm;

	jrawMonitorID lock;

} GlobalAgentData;

static GlobalAgentData *gdata;

static void check_jvmti_error(jvmtiEnv *jvmti, jvmtiError errnum,
		const char *str) {
	if (errnum != JVMTI_ERROR_NONE) {
		char *errnum_str;

		errnum_str = NULL;
		(void) jvmti->GetErrorName(errnum, &errnum_str);

		printf("ERROR: JVMTI: %d(%s): %s\n", errnum,
				(errnum_str == NULL ? "Unknown" : errnum_str),
				(str == NULL ? "" : str));
	}
}
void fatal_error(const char * format, ...) {
	va_list ap;

	va_start(ap, format);
	(void) vfprintf(stderr, format, ap);
	(void) fflush(stderr);
	va_end(ap);
	exit(3);
}


/* Enter a critical section by doing a JVMTI Raw Monitor Enter */
static void enter_critical_section(jvmtiEnv *jvmti) {
	jvmtiError error;

	error = jvmti->RawMonitorEnter(gdata->lock);
	check_jvmti_error(jvmti, error, "Cannot enter with raw monitor");
}

/* Exit a critical section by doing a JVMTI Raw Monitor Exit */
static void exit_critical_section(jvmtiEnv *jvmti) {
	jvmtiError error;

	error = jvmti->RawMonitorExit(gdata->lock);
	check_jvmti_error(jvmti, error, "Cannot exit with raw monitor");
}

static int n = 0;
// Implementation of _dump JNI function
JNIEXPORT static void JNICALL dump_CRIU(JNIEnv *env, jclass klass,
		jobject o, jobject expr) {

    char fileName [100];
    sprintf(fileName, "criu.log.%d",n);
    criu_set_log_file(fileName);
    n++;
    printf("DUMP to %s\n",fileName);

    int ret = criu_dump();
	if(ret<0)
		printf("ERROR: FAILED TO CRIU CHECKPOINT, %d\n",ret);
}

// Implementation of _restore JNI function
JNIEXPORT static void JNICALL restore_CRIU(JNIEnv *env, jclass klass,
		jobject o, jobject expr) {

    printf("RESTORE\n");

}

/*
 * Callback we get when the JVM is initialized.
 */
static void JNICALL callbackVMInit(jvmtiEnv * jvmti, JNIEnv * env, jthread thread)
{
}
/*
 * Callback we receive when the JVM terminates - no more functions can be called after this
 */
static void JNICALL callbackVMDeath(jvmtiEnv *jvmti_env, JNIEnv* jni_env) {
//	gdata->vmDead = JNI_TRUE;
//	if(gdata->lockPolicy)
//	{
//		jni_env->DeleteGlobalRef(gdata->lockPolicy);
//	}
}

/*
 * Callback we get when the JVM starts up, but before its initialized.
 * Sets up the JNI calls.
 */
static void JNICALL cbVMStart(jvmtiEnv *jvmti, JNIEnv *env) {

	enter_critical_section(jvmti);
	{
		jclass klass;
		jfieldID field;
		jint rc;

		static JNINativeMethod registry[] = {
				{ "_dump", "()V", (void*) &dump_CRIU },
				{ "_restore", "()V", (void*) &restore_CRIU },
    };
		/* Register Natives for class whose methods we use */
		klass = env->FindClass("net/jonbell/crij/runtime/CRIU");
		if (klass == NULL) {
			fatal_error(
					"ERROR: JNI: Cannot find CRIU with FindClass\n");
		}
		rc = env->RegisterNatives(klass, registry, (sizeof(registry)/sizeof(*registry)));
		if (rc != 0) {
			fatal_error(
					"ERROR: JNI: Cannot register natives for CRIU\n");
		}
		/* Engage calls. */
		field = env->GetStaticFieldID(klass, "engaged", "I");
		if (field == NULL) {
			fatal_error("ERROR: JNI: Cannot get field\n"
			);
		}
		env->SetStaticIntField(klass, field, 1);

		/* Indicate VM has started */
		gdata->vm_is_started = JNI_TRUE;

		// CRIU stuff
		criu_init_opts();

    const char * criu_dir = getenv("CROCHET_CRIU_DIR");
    if (criu_dir == NULL)
        criu_dir = "criu-dump";

		int fd = open(criu_dir, O_DIRECTORY);
    if (fd < 0) {
        dprintf(2, "Could not open CRIU dump dir: %s\n", criu_dir);
        dprintf(2, "Please make sure it exists, or set it correctly using env variable CROCHET_CRIU_DIR\n");
        abort();
    }

		criu_set_images_dir_fd(fd);

		criu_set_tcp_established(true);
		criu_set_log_file("criu.log");
		criu_set_log_level(4);
		criu_set_shell_job(1);
		criu_set_ext_unix_sk(1);
		criu_set_evasive_devices(1);
		criu_set_leave_running(1);

	}
	exit_critical_section(jvmti);
}

/*
 * Callback that is notified when our agent is loaded. Registers for event
 * notifications.
 */
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options,
		void *reserved) {
	static GlobalAgentData data;
	jvmtiError error;
	jint res;
	jvmtiEventCallbacks callbacks;
	jvmtiEnv *jvmti = NULL;
	jvmtiCapabilities capa;
	int withStack = 0;

	(void) memset((void*) &data, 0, sizeof(data));
	gdata = &data;
	gdata->jvm = jvm;
	res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_1_0);

	if (res != JNI_OK || jvmti == NULL) {
		/* This means that the VM was unable to obtain this version of the
		 *   JVMTI interface, this is a fatal error.
		 */
		printf("ERROR: Unable to access JVMTI Version 1 (0x%x),"
				" is your J2SE a 1.5 or newer version?"
				" JNIEnv's GetEnv() returned %d\n", JVMTI_VERSION_1, res);

	}
	//save jvmti for later
	gdata->jvmti = jvmti;

	//Register our capabilities
	(void) memset(&capa, 0, sizeof(jvmtiCapabilities));
//	capa.can_signal_thread = 1;
//  capa.can_generate_object_free_events = 1;
//	capa.can_tag_objects = 1;
//	capa.can_generate_garbage_collection_events = 1;
//	capa.can_set_native_method_prefix = 1;
//	if(withStack)
//	{
//		gdata->withStackSupport = true;
//		capa.can_access_local_variables = 1;
////		capa.can_generate_frame_pop_events = 1;
//		capa.can_suspend = 1;
//		capa.can_generate_single_step_events = 1;
//		capa.can_get_bytecodes = 1;
//		capa.can_force_early_return = 1;
//		capa.can_pop_frame = 1;
//		capa.can_get_owned_monitor_info = 1;
//	}
	error = jvmti->AddCapabilities(&capa);
	check_jvmti_error(jvmti, error,
			"Unable to get necessary JVMTI capabilities.");

	//Register callbacks
	(void) memset(&callbacks, 0, sizeof(callbacks));
	callbacks.VMInit = &callbackVMInit;
	callbacks.VMDeath = &callbackVMDeath;
	callbacks.VMStart = &cbVMStart;

	error = jvmti->SetEventCallbacks(&callbacks, (jint) sizeof(callbacks));
	check_jvmti_error(jvmti, error, "Cannot set jvmti callbacks");

	error = jvmti->CreateRawMonitor("agent data", &(gdata->lock));
	check_jvmti_error(jvmti, error, "Cannot create raw monitor");

	//Register for events
	error = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_START,
			(jthread) NULL);
	check_jvmti_error(jvmti, error, "Cannot set event notification");
	error = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT,
			(jthread) NULL);
	check_jvmti_error(jvmti, error, "Cannot set event notification");
	error = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_DEATH,
			(jthread) NULL);
	check_jvmti_error(jvmti, error, "Cannot set event notification");

	return JNI_OK;
}
