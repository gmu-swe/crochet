#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <string.h>
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

	jboolean withStackSupport;

	/* Data access Lock */
	jrawMonitorID lock;
	JavaVM* jvm;
	jmethodID checkpointMethod;
	jmethodID rollbackMethod;
	jmethodID traverseStaticFieldsMethod;
	jmethodID wait;
	jmethodID notify;
	jmethodID rollForwardQueueMethod;
	jmethodID rollForwardGoMethod;
	jmethodID init;
	jmethodID lockThread;
	jmethodID unlockThread;

	jclass taggerClass;
	jclass rollForwardClass;
	jclass classClass;
	jclass objectWrapperKlass;
	jclass staticFieldWalkerClass;
	jobject lockPolicy;

} GlobalAgentData;
typedef struct DeleteQueue {
	jobject obj;
	DeleteQueue * next;
} DeleteQueue;
typedef struct StackEntry {
	jvmtiParamTypes type;
	int slot;
	jvalue val;
	StackEntry * next;
	StackEntry * prev;
} StackEntry;
typedef struct CheckpointedStackFrame {
//	long vals[];
	int version;
	int height;
	jmethodID meth;
	jlocation loc;
	jint nBytecodes;
	bool isTop;
	bool ignored;
	unsigned char* bytecodes;

	StackEntry* operandStack;
	StackEntry* entries;
	StackEntry* entriesTail;

	CheckpointedStackFrame* old;
	CheckpointedStackFrame* next;
	CheckpointedStackFrame* prev; //used when replaying
} CheckpointedStackFrame;
typedef struct ThreadLocalInfo {
	volatile bool inRollback;
	bool ignored;
	CheckpointedStackFrame * replayFrame;
	CheckpointedStackFrame * curFrame;
	CheckpointedStackFrame * allFrames;
	jint nOwnedMonitorsAtCheckpoint;
	jobject *ownedMonitorsAtCheckpoint;
};
//static CheckpointedFrame frames[];
//Queue of global references that need to be cleaned up
static DeleteQueue * deleteQueue = NULL;
static jrawMonitorID deleteQueueLock;
static jrawMonitorID stackPopLock;
static jobject stackLock;

static GlobalAgentData *gdata;
static jint version;
static jthread callingThread;
static bool ignoreCurrentThread;
static bool shouldCheckpoint;

static jint checkpointDepth;

#define readU4Le(bytes) (((U_32)(bytes)[3]<<24) | ((U_32)(bytes)[2]<<16) | \
                         ((U_32)(bytes)[1]<<8)  | (bytes)[0])

#define readU4Be(bytes) (((U_32)(bytes)[0]<<24) | ((U_32)(bytes)[1]<<16) | \
                         ((U_32)(bytes)[2]<<8)  | (bytes)[3])
void fatal_error(const char * format, ...) {
	va_list ap;

	va_start(ap, format);
	(void) vfprintf(stderr, format, ap);
	(void) fflush(stderr);
	va_end(ap);
	exit(3);
}

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


static void describeLocation(jlocation loc, jmethodID method) {
	jvmtiError err;
	char *methodName;
	char *methodSignature;
	jclass klass;
	char *className;
	jint lineNumberCount;
	jvmtiLineNumberEntry * lineNumbers;
	int lineNumber = -1;
	int i;
	err = gdata->jvmti->GetMethodName(method, &methodName, &methodSignature, NULL);
	check_jvmti_error(gdata->jvmti, err, "Unable to get source info1");
	err = gdata->jvmti->GetMethodDeclaringClass(method, &klass);
	check_jvmti_error(gdata->jvmti, err, "Unable to get source info2");
	err = gdata->jvmti->GetClassSignature(klass, &className, NULL);
	check_jvmti_error(gdata->jvmti, err, "Unable to get source info3");

	err = gdata->jvmti->GetLineNumberTable(method, &lineNumberCount, &lineNumbers);
	if (err == JVMTI_ERROR_NONE) {
		for (i = 0; i < lineNumberCount; i++) {
			if (lineNumbers[i].start_location < loc) {
				lineNumber = lineNumbers[i].line_number;
			}
		}
		gdata->jvmti->Deallocate((unsigned char *) lineNumbers);
	}
	if (lineNumber >= 0)
		printf("%s.%s%s Line %d", className, methodName, methodSignature,
				lineNumber);
	else if (err == JVMTI_ERROR_NATIVE_METHOD)
		printf("%s.%s%s (Native Method)", className, methodName,
				methodSignature);
	else
		printf("%s.%s%s (No line information available)", className, methodName,
				methodSignature);
	gdata->jvmti->Deallocate((unsigned char *) className);
	gdata->jvmti->Deallocate((unsigned char *) methodName);
	gdata->jvmti->Deallocate((unsigned char *) methodSignature);
}
static void printStrackTrace() {
	jvmtiFrameInfo frames[10];
	int i;
	jint nframes;
	jvmtiThreadInfo fi;
	gdata->jvmti->GetThreadInfo(NULL,&fi);

	gdata->jvmti->GetStackTrace(NULL, 0, 10, frames, &nframes);
	printf("Trace of %s:\n",fi.name);
	gdata->jvmti->Deallocate((unsigned char*)fi.name);

	for (i = 0; i < nframes; i++) {
		printf("\t");
		describeLocation(frames[i].location, frames[i].method);
		printf("\n");
	}

}
static void freeStackEntry(JNIEnv* jni, StackEntry *e)
{
	if((e->type == JVMTI_TYPE_JOBJECT || e->type == JVMTI_TYPE_JVALUE) && e->val.l)
		jni->DeleteGlobalRef(e->val.l);
	delete e;
}
static void freeStackFrame(JNIEnv* jni, CheckpointedStackFrame * p)
{
	StackEntry *e = p->entries;
	while (p->entries) {
		e = p->entries->next;
		freeStackEntry(jni, p->entries);
		p->entries = e;
	}
	e = p->operandStack;
	while (p->operandStack) {
		e = p->operandStack->next;
		freeStackEntry(jni, p->operandStack);
		p->operandStack = e;
	}
	CheckpointedStackFrame *t = p->old;
	while (p->old) {
		t = p->old->old;
		delete p->old;
		p->old = t;
	}
	delete p;
}

void JNICALL step(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread,
		jmethodID method, jlocation location) {
	jvmtiError err;
	CheckpointedStackFrame *frame;
	unsigned char op;
	StackEntry * e = NULL;
	ThreadLocalInfo *tinfo;
	err = jvmti->GetThreadLocalStorage(thread, (void**) &tinfo);
	frame = tinfo->curFrame;
//	jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);

//	describeLocation(location,method);
//	printf("Stepping %d..\n",location);
	if (frame && frame->next) {
		frame = frame->next;
//		printf("\t");
//		describeLocation(frame->loc,frame->meth);
//		printf("\n");
		if (frame->meth != method) {
			err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
					JVMTI_EVENT_SINGLE_STEP, thread);
			check_jvmti_error(jvmti, err, "Cannot set event notification");
			if(tinfo->inRollback)
			{
//				describeLocation(location, method);
//											printf("\tsuspending op is unknown\n");
											err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
											check_jvmti_error(jvmti, err, "Turning off step");
											tinfo->inRollback = 0;

											err = jvmti->SuspendThread(thread);

											check_jvmti_error(jvmti, err, "Suspending");

											return;
			}
			return;
		}
		if (frame->nBytecodes == 0) {
			err = jvmti->GetBytecodes(method, &(frame->nBytecodes),
					&(frame->bytecodes));
			check_jvmti_error(jvmti, err, "Cannot get bytecodes");
		}
		op = frame->bytecodes[location];
//		printf("OP is %d\n",op);
		if (frame->operandStack) {
			//Process remaining vars
			switch (op) {
			case 54:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JINT;
				e->next = frame->operandStack->next;
				e->slot = (int) frame->bytecodes[location + 1];
				frame->operandStack->next = e;
				break;
			case 55:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JLONG;
				e->next = frame->operandStack->next;
				e->slot = (int) frame->bytecodes[location + 1];
				frame->operandStack->next = e;
				break;
			case 56:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JFLOAT;
				e->next = frame->operandStack->next;
				e->slot = (int) frame->bytecodes[location + 1];
				frame->operandStack->next = e;
				break;
			case 57:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JDOUBLE;
				e->next = frame->operandStack->next;
				e->slot = (int) frame->bytecodes[location + 1];
				frame->operandStack->next = e;
				break;
			case 58:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JOBJECT;
				e->next = frame->operandStack->next;
				e->slot = (int) frame->bytecodes[location + 1];
				frame->operandStack->next = e;
				break;
			case 59:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JINT;
				e->next = frame->operandStack->next;
				e->slot = 0;
				frame->operandStack->next = e;
				break;
			case 60:
				e = new StackEntry();
				e->slot = 1;
				e->type = JVMTI_TYPE_JINT;
				e->next = frame->operandStack->next;
				frame->operandStack->next = e;
				break;
			case 61:
				e = new StackEntry();
				e->slot = 2;
				e->type = JVMTI_TYPE_JINT;
				e->next = frame->operandStack->next;
				frame->operandStack->next = e;
				break;
			case 62:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JINT;
				e->next = frame->operandStack->next;
				e->slot = 3;
				frame->operandStack->next = e;
				break;
			case 63:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JLONG;
				e->next = frame->operandStack->next;
				e->slot = 0;
				frame->operandStack->next = e;
				break;
			case 64:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JLONG;
				e->next = frame->operandStack->next;
				e->slot = 1;
				frame->operandStack->next = e;
				break;
			case 65:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JLONG;
				e->next = frame->operandStack->next;
				e->slot = 2;
				frame->operandStack->next = e;
				break;
			case 66:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JLONG;
				e->next = frame->operandStack->next;
				e->slot = 3;
				frame->operandStack->next = e;
				break;
			case 67:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JFLOAT;
				e->next = frame->operandStack->next;
				e->slot = 0;
				frame->operandStack->next = e;
				break;
			case 68:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JFLOAT;
				e->next = frame->operandStack->next;
				e->slot = 1;
				frame->operandStack->next = e;
				break;
			case 69:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JFLOAT;
				e->next = frame->operandStack->next;
				e->slot = 2;
				frame->operandStack->next = e;
				break;
			case 70:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JFLOAT;
				e->next = frame->operandStack->next;
				e->slot = 3;
				frame->operandStack->next = e;
				break;
			case 71:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JDOUBLE;
				e->next = frame->operandStack->next;
				e->slot = 0;
				frame->operandStack->next = e;
				break;
			case 72:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JDOUBLE;
				e->next = frame->operandStack->next;
				e->slot = 1;
				frame->operandStack->next = e;
				break;
			case 73:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JDOUBLE;
				e->next = frame->operandStack->next;
				e->slot = 2;
				frame->operandStack->next = e;
				break;
			case 74:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JDOUBLE;
				e->next = frame->operandStack->next;
				e->slot = 3;
				frame->operandStack->next = e;
				break;
			case 75:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JOBJECT;
				e->next = frame->operandStack->next;
				e->slot = 0;
				frame->operandStack->next = e;
				break;
			case 76:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JOBJECT;
				e->next = frame->operandStack->next;
				e->slot = 1;
				frame->operandStack->next = e;
				break;
			case 77:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JOBJECT;
				e->next = frame->operandStack->next;
				e->slot = 2;
				frame->operandStack->next = e;
				break;
			case 78:
				e = new StackEntry();
				e->type = JVMTI_TYPE_JOBJECT;
				e->next = frame->operandStack->next;
				e->slot = 3;
				frame->operandStack->next = e;
				break;
			default:
				/*
				 * After the NOP, then after all of the X_STORE_N instructions, we can
				 * retrieve all of the stack values.
				 */
				err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
						JVMTI_EVENT_SINGLE_STEP, thread);
				check_jvmti_error(jvmti, err, "Cannot set event notification");

				e = frame->operandStack->next;
				while (e) {
					if (e->type == JVMTI_TYPE_JOBJECT) {
						err = jvmti->GetLocalObject(thread, 0, e->slot,
								&(e->val.l));
						check_jvmti_error(jvmti, err,
								"Unable to get local var");
						if(e->val.l)
						{
							e->val.l = jni->NewGlobalRef(e->val.l);
							jni->CallStaticVoidMethod(gdata->objectWrapperKlass,
									gdata->checkpointMethod, e->val.l, version);
							if (jni->ExceptionCheck()) {
								printf("ERROR: Exception calling checkpoint\n");
							}
						}
					} else if (e->type == JVMTI_TYPE_JFLOAT) {
						err = jvmti->GetLocalFloat(thread, 0, e->slot,
								&(e->val.f));
						check_jvmti_error(jvmti, err,
								"Unable to get local var");
					} else if (e->type == JVMTI_TYPE_JDOUBLE) {
						err = jvmti->GetLocalDouble(thread, 0, e->slot,
								&(e->val.d));
						check_jvmti_error(jvmti, err,
								"Unable to get local var");
					} else if (e->type == JVMTI_TYPE_JLONG) {
						err = jvmti->GetLocalLong(thread, 0, e->slot,
								&(e->val.j));
						check_jvmti_error(jvmti, err,
								"Unable to get local var");
					} else if (e->type == JVMTI_TYPE_JINT) {
						err = jvmti->GetLocalInt(thread, 0, e->slot,
								&(e->val.i));
						check_jvmti_error(jvmti, err,
								"Unable to get local var");
					}
					e = e->next;
				}
				if(tinfo->inRollback)
						{
//							describeLocation(location, method);
//							printf("\tsuspending op is %d\n",op);
							err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
							check_jvmti_error(jvmti, err, "Turning off step");
							tinfo->inRollback = 0;

							err = jvmti->SuspendThread(thread);

							check_jvmti_error(jvmti, err, "Suspending");

							return;
						}
			}
		}
		else if(op == 0)
		{
#ifdef PRINT_DEBUG
			describeLocation(location,method);
			printf("<<pulling stack togther\n");
#endif
			frame->operandStack = new StackEntry();
			frame->operandStack->next = NULL;
			frame->operandStack->prev = NULL;
		}
		else
		{
			err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
					JVMTI_EVENT_SINGLE_STEP, thread);
			check_jvmti_error(jvmti, err, "Cannot set event notification");
			if(tinfo->inRollback)
					{
						describeLocation(location, method);
						printf("\tsuspending op is %d\n",op);
						err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
						check_jvmti_error(jvmti, err, "Turning off step");
						tinfo->inRollback = 0;

						err = jvmti->SuspendThread(thread);

						check_jvmti_error(jvmti, err, "Suspending");

						return;
					}
		}
	}else
	{
		if(tinfo->inRollback)
		{
			describeLocation(location, method);
			printf("\t2222suspending\n");
			err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
			check_jvmti_error(jvmti, err, "Turning off step");
			tinfo->inRollback = 0;

			err = jvmti->SuspendThread(thread);

			check_jvmti_error(jvmti, err, "Suspending");

			return;
		}
		err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
							JVMTI_EVENT_SINGLE_STEP, thread);
					check_jvmti_error(jvmti, err, "Cannot set event notification");
	}
}
static bool isIgnoredFrame(jmethodID meth)
{
	jclass cl;
	char *name;
	gdata->jvmti->GetMethodDeclaringClass(meth,&cl);
	gdata->jvmti->GetClassSignature(cl, &name, NULL);
	if(strncmp("Lnet/jonbell/crij/runtime",name,strlen("Lnet/jonbell/crij/runtime")) == 0)
	{
		gdata->jvmti->Deallocate((unsigned char*)name);
		return 1;
	}
	gdata->jvmti->Deallocate((unsigned char*)name);
	return 0;
}

static bool isIgnoredThread(jthread thread)
{
	jvmtiThreadInfo fi;
	gdata->jvmti->GetThreadInfo(thread,&fi);
	if(strncmp("Finalizer",fi.name,strlen("Finalizer")) == 0)
	{
		gdata->jvmti->Deallocate((unsigned char*)fi.name);
		return 1;
	}
	gdata->jvmti->Deallocate((unsigned char*)fi.name);
	return 0;
}


static void doCheckpoint(jvmtiEnv* jvmti, JNIEnv* jni) {
	jvmtiError err;
	int ti;
	jint thread_count;
	jvmtiStackInfo* stack_info;
	jobject obj;
	int lvi;
	ThreadLocalInfo* tinfo;
	CheckpointedStackFrame* firstOldStackFrame;
	CheckpointedStackFrame* lastStackFrame;
	CheckpointedStackFrame* newStackFrame;
	StackEntry* e;
	int fi;
	bool isStatic;
	jint mods;
	int height;
	int actualDepthToUse;
	bool isCallingThread;
	bool found;
	jboolean isNativeMethod;

#ifdef PRINT_DEBUG
	printf("CHECKPOINT: Traversing static fields\n");
#endif
  // Traverse all static fields
  jni->CallStaticVoidMethod(gdata->staticFieldWalkerClass,
          gdata->traverseStaticFieldsMethod, JNI_TRUE, version);
#ifdef PRINT_DEBUG
	printf("Traversing done\n");
#endif

  if (jni->ExceptionCheck()) {
    printf("ERROR: Exception calling staticFieldsCheckpoint\n");
  }

	err = jvmti->GetAllStackTraces(500, &stack_info, &thread_count);
	check_jvmti_error(jvmti, err, "Cant get stack traces");
	for (ti = 0; ti < thread_count; ++ti) {
		jvmtiStackInfo *infop = &stack_info[ti];
		jthread thread = infop->thread;
		isCallingThread = jni->IsSameObject(thread,callingThread);
		if(ignoreCurrentThread && isCallingThread)
		{
			continue;
		}
		if(isIgnoredThread(thread))
			continue;
		jvmtiThreadInfo jtinfo;
#ifdef PRINT_DEBUG
		jvmti->GetThreadInfo(thread, &jtinfo);
		printf("WOrking on thread %s\n", jtinfo.name);
		jvmti->Deallocate((unsigned char*) jtinfo.name);
#endif
		jvmti->GetThreadLocalStorage(thread, (void**) &tinfo);
		if (!tinfo) {
			tinfo = new ThreadLocalInfo();
			tinfo->inRollback = 0;
			lastStackFrame = new CheckpointedStackFrame();
			lastStackFrame->old = NULL;
			lastStackFrame->height = 0;
			lastStackFrame->entries = NULL;
			lastStackFrame->entriesTail = NULL;
			lastStackFrame->next = NULL;
			tinfo->curFrame = lastStackFrame;
			tinfo->allFrames = lastStackFrame;
			jvmti->SetThreadLocalStorage(thread, tinfo);
		}
		else
			lastStackFrame = tinfo->curFrame;
		firstOldStackFrame = lastStackFrame->next;

		for (fi = (isCallingThread ? 2 : 0); fi < infop->frame_count; fi++) {
			if (tinfo->ignored)
				continue;
#ifdef PRINT_DEBUG
			describeLocation(infop->frame_buffer[fi].location, infop->frame_buffer[fi].method);
			printf("<< checking for native\n");
#endif
			jvmti->IsMethodNative(infop->frame_buffer[fi].method,
					&isNativeMethod);
			if (isNativeMethod) {
#ifdef PRINT_DEBUG
				jvmti->GetThreadInfo(thread, &jtinfo);
				printf("Setting thread ignored: %s\n", jtinfo.name);
				jvmti->Deallocate((unsigned char*) jtinfo.name);
				describeLocation(NULL, infop->frame_buffer[fi].method);
				printf("<<because of frame %d %d\n", fi, infop->frame_count);
#endif

				tinfo->ignored = 1;
			}
		}
		if (tinfo->ignored)
			continue;
		/*
		 * Get the owned monitors
		 */
		err = jvmti->GetOwnedMonitorInfo(thread,&(tinfo->nOwnedMonitorsAtCheckpoint), &(tinfo->ownedMonitorsAtCheckpoint));
		check_jvmti_error(jvmti,err,"Couldn't steal monitor info");


		for (fi = (isCallingThread ? 2 : 0); fi < infop->frame_count; fi++) {
#ifdef PRINT_DEBUG
			describeLocation(infop->frame_buffer[fi].location,
					infop->frame_buffer[fi].method);
						printf("CP, BCI%d\n",infop->frame_buffer[fi].location);
#endif

			if(isCallingThread && isIgnoredFrame(infop->frame_buffer[fi].method))
			{
				//See if this method is actually a runtime method
				actualDepthToUse++;
				continue;
			}
			err = gdata->jvmti->GetLocalObject(thread, fi, 0, &obj);
			if (err == JVMTI_ERROR_OPAQUE_FRAME)
				continue; //Not visible frame
			height = infop->frame_count - fi;
			newStackFrame = new CheckpointedStackFrame();
			newStackFrame->entries = NULL;
			newStackFrame->old = NULL;
			newStackFrame->meth = infop->frame_buffer[fi].method;
			newStackFrame->entriesTail = NULL;
			newStackFrame->height = height;
			newStackFrame->loc = infop->frame_buffer[fi].location;
			newStackFrame->nBytecodes = 0;
			newStackFrame->operandStack = NULL;
			newStackFrame->ignored = isIgnoredFrame(infop->frame_buffer[fi].method);
			newStackFrame->isTop = 0;
			lastStackFrame->next = newStackFrame;
			lastStackFrame = lastStackFrame->next;
//			if(firstOldStackFrame)
//			{
////				describeLocation(infop->frame_buffer[fi].location,infop->frame_buffer[fi].method);
////				printf(" (h=%d) VS (h=%d) ",firstOldStackFrame->height,newStackFrame->height);
////				describeLocation(NULL,firstOldStackFrame->meth);
//				//There is an old one sitting around...
//				if(infop->frame_buffer[fi].method == firstOldStackFrame->meth && height == firstOldStackFrame->height)
//				{
//					//Set the "next one to look at" to be the old one
//					lastStackFrame->old = firstOldStackFrame;
//					//Set the next old one to look at to be the nexct there
//					firstOldStackFrame = firstOldStackFrame->next;
//					//And remove that from the list
//					lastStackFrame->old->next = NULL;
//				}
//			}

			//			if (fi > 2)
			//Set the flag to trip the agent when the calling method returns

			//			printf("Done\n");

			jvmti->GetMethodModifiers(infop->frame_buffer[fi].method, &mods);

			isStatic = ((mods & 0x0008) != 0);


			if (newStackFrame->nBytecodes == 0) {
				err = jvmti->GetBytecodes(infop->frame_buffer[fi].method,
						&(newStackFrame->nBytecodes),
						&(newStackFrame->bytecodes));
				check_jvmti_error(jvmti, err, "Cannot get bytecodes");
			}
			int lv = -1;
			if (newStackFrame->bytecodes[0] == 0 //NOP
			&& newStackFrame->bytecodes[1] == 3 //ICONST_0
					) {

			}
			switch (newStackFrame->bytecodes[2]) {
			case 54:
				lv = (int) newStackFrame->bytecodes[3];
				break;
			case 59:
				lv = 0;
				break;
			case 60:
				lv = 1;
				break;
			case 61:
				lv = 2;
				break;
			case 62:
				lv = 3;
				break;
			default:
//				printf(
//						"Unknown bytecode index in call stack (should be ISTORE!):%d\n",
//						newStackFrame->bytecodes[2]);
				newStackFrame->ignored = 1;
				continue;
			}
//			printf("Working on locals up to %d\n",lv);

			//We set that local int to be the version that we are going to be checkpointing to :)
			err = gdata->jvmti->SetLocalInt(thread, fi, lv, version);
			check_jvmti_error(gdata->jvmti, err, "Unable to toggle recorder");
			//Save the LVs
			for (lvi = 0; lvi < 255; lvi++) {
//				printf("\t%d, d = %d\n", lvi,fi);
				err = gdata->jvmti->GetLocalObject(thread, fi, lvi, &obj);
//				check_jvmti_error(jvmti, err, "Couldnt get var");
//				printf("Z\n");

//								if (err == JVMTI_ERROR_INVALID_SLOT)
//									break;
				//				else

				e = new StackEntry();
				e->slot = lvi;
				e->next = NULL;
				e->prev = lastStackFrame->entriesTail;
				if (!lastStackFrame->entries) {
					lastStackFrame->entries = e;
					lastStackFrame->entriesTail = e;
				} else
					lastStackFrame->entriesTail->next = e;
				lastStackFrame->entriesTail = e;

				if (err == JVMTI_ERROR_TYPE_MISMATCH) {
					//Not OBJ
					err = jvmti->GetLocalLong(thread, fi, lvi, &e->val.j);
					if (err == JVMTI_ERROR_TYPE_MISMATCH) {
						err = jvmti->GetLocalDouble(thread, fi, lvi, &e->val.d);
						if (err == JVMTI_ERROR_TYPE_MISMATCH) {
							err = jvmti->GetLocalFloat(thread, fi, lvi,
									&e->val.f);
							if (err == JVMTI_ERROR_TYPE_MISMATCH) {
								err = jvmti->GetLocalInt(thread, fi, lvi,
										&e->val.i);
								if (err == JVMTI_ERROR_TYPE_MISMATCH) {
									printf("No idea wtf this var is %d\n", lvi);
								} else {
									check_jvmti_error(gdata->jvmti, err,
											"Unable to get LV");
									e->type = JVMTI_TYPE_JINT;
								}
							} else {
								check_jvmti_error(gdata->jvmti, err,
										"Unable to get LV");
								e->type = JVMTI_TYPE_JFLOAT;
							}
						} else {
							check_jvmti_error(gdata->jvmti, err,
									"Unable to get LV");
							e->type = JVMTI_TYPE_JDOUBLE;
							lvi++;
						}
					} else {
						check_jvmti_error(gdata->jvmti, err,
								"Unable to get LV");
						e->type = JVMTI_TYPE_JLONG;
						lvi++;
					}
				} else {

					if (!isStatic && lvi == 0)
						e->type = JVMTI_TYPE_JVALUE;
					else
						e->type = JVMTI_TYPE_JOBJECT;
					//						check_jvmti_error(gdata->jvmti, err,
					//								"Unable to get obj");
					//TODO this should be called INSTEAD immediately once we return into this method!
					if (obj) {
						e->val.l = jni->NewGlobalRef(obj);
					} else
						e->val.l = NULL;
				}

			}
			//Make sure that it didn't bizzarely put something weird in here
			found = false;
			e = lastStackFrame->entries;
			while(e->next)
			{
				e = e->next;
				if(e->slot == lv){
					found =true;
					e->type= JVMTI_TYPE_JINT;
					e->val.i = 0;
					break;
				}
			}
			if (!found) {
				e = new StackEntry();
				e->slot = lv;
				e->next = NULL;
				e->prev = lastStackFrame->entriesTail;
				if (!lastStackFrame->entries) {
					lastStackFrame->entries = e;
					lastStackFrame->entriesTail = e;
				} else
					lastStackFrame->entriesTail->next = e;
				lastStackFrame->entriesTail = e;
				e->type = JVMTI_TYPE_JINT;
				e->val.i = 0;
				printf("Inserted LV %d\n", e->slot);
			}
//			describeLocation(infop->frame_buffer[fi].location,
//					infop->frame_buffer[fi].method);

		}
	}
	err = gdata->jvmti->Deallocate((unsigned char*) stack_info);
	if (callingThread) {
		jni->DeleteGlobalRef(callingThread);
		callingThread = NULL;
	}
}

static void doRollback(jvmtiEnv* jvmti, JNIEnv* jni) {
	jvmtiError err;
	int ti;
	jint thread_count;
	jvmtiStackInfo* stack_info;
	jobject obj;
	jvmtiThreadInfo jtinfo;
	int lvi;
	ThreadLocalInfo *tinfo;
	CheckpointedStackFrame * stackFrame;
	CheckpointedStackFrame * previousStackFrame;
	jint threadState;
	jclass clazz;
	char *mname;
	char *mdesc;
	char *calldesc;
	jint previousCallModifiers;
	StackEntry * e;
	StackEntry * t;
	bool doFree;
	jboolean isNativeMethod;
	int height;
	jint methodKey;
	int fi;

#ifdef PRINT_DEBUG
	printf("ROLLBACK: Traversing fields\n");
#endif
	// Traverse all static fields
	jni->CallStaticVoidMethod(gdata->staticFieldWalkerClass,
			gdata->traverseStaticFieldsMethod, JNI_FALSE, version);
	if (jni->ExceptionCheck()) {
		printf("ERROR: Exception calling staticFieldsCheckpoint\n");
	}
#ifdef PRINT_DEBUG
	printf("ROLLBACK: Getting Stack traces\n");
#endif
	err = jvmti->GetAllStackTraces(500, &stack_info, &thread_count);
	check_jvmti_error(jvmti, err, "Cant get stack traces");
	for (ti = 0; ti < thread_count; ++ti) {
		jvmtiStackInfo *infop = &stack_info[ti];
		jthread thread = infop->thread;
		jvmti->GetThreadLocalStorage(thread, (void**) &tinfo);
		if(!tinfo)
			continue;
#ifdef PRINT_DEBUG
		jvmti->GetThreadInfo(thread, &jtinfo);
		printf("Examining thread %s %lld\n", jtinfo.name, thread);
		jvmti->Deallocate((unsigned char*) jtinfo.name);
#endif
		if(ignoreCurrentThread && jni->IsSameObject(thread,callingThread))
		{
#ifdef PRINT_DEBUG
			printf("Ignored\n");
#endif
			tinfo->ignored=1;
			continue;
		}
		if(tinfo->ignored)
		{
#ifdef PRINT_DEBUG
			printf("Ignored\n");
#endif
			continue;
		}
#ifdef PRINT_DEBUG
		jvmti->GetThreadInfo(thread, &jtinfo);
		printf("Working on thread %s\n", jtinfo.name);
		jvmti->Deallocate((unsigned char*) jtinfo.name);
#endif
		tinfo->ignored = 0;

		err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
				JVMTI_EVENT_SINGLE_STEP, thread);
		check_jvmti_error(jvmti, err, "Cannot set event notification");
		//First find out if we are going to ignore this thread b/c it has native code somewhere
		for(fi = 0; fi < infop->frame_count; fi++) {
			if(tinfo->ignored)
				break;
#ifdef PRINT_DEBUG
			describeLocation(infop->frame_buffer[fi].location, infop->frame_buffer[fi].method);
			printf("<< checking for native\n");
#endif
			jvmti->IsMethodNative(infop->frame_buffer[fi].method,&isNativeMethod);
			if(isNativeMethod)
			{
#ifdef PRINT_DEBUG
				jvmti->GetThreadInfo(thread, &jtinfo);
				printf("Setting thread ignored: %s\n", jtinfo.name);
				jvmti->Deallocate((unsigned char*) jtinfo.name);
				describeLocation(NULL, infop->frame_buffer[fi].method);
				printf("<<because of frame %d %d\n", fi, infop->frame_count);
#endif

				tinfo->ignored = 1;
			}
		}

		//Go through every thread and unwind its stack
		if(!tinfo->ignored)
		for (fi = 0; fi < infop->frame_count; fi++) {
			height = infop->frame_count - fi;
#ifdef PRINT_DEBUG
			describeLocation(NULL, infop->frame_buffer[fi].method);
			printf("Bailing out of frame %d %d\n", fi, infop->frame_count);
#endif

#ifdef PRINT_DEBUG
				printf("Curframe: ");
				describeLocation(NULL,tinfo->curFrame->next->meth);
				printf("\n");
#endif

			if (infop->frame_count == fi + 2) {
				//Got to the bottom. For now let's just bail.
#ifdef PRINT_DEBUG
				printf("Bottom\n");
				describeLocation(NULL,tinfo->curFrame->next->meth);
				printf("At the bottom\n");
#endif
				tinfo->curFrame->next->isTop = true;
				err = jvmti->PopFrame(thread);
				check_jvmti_error(jvmti,err,"Unable to pop bottom");
				break;
			}
			//Find out what kind of method this is so we know what to force return.
			err = jvmti->GetMethodName(infop->frame_buffer[fi].method, NULL,
					&mdesc, NULL);
			check_jvmti_error(jvmti, err,
					"Unable to get method desc to force return");
			char t = strstr(mdesc, ")")[1];
			switch (t) {
			case 'V':
				err = jvmti->ForceEarlyReturnVoid(thread);
				break;
			case '[':
			case 'L':
				err = jvmti->ForceEarlyReturnObject(thread, NULL);
				break;
			case 'S':
			case 'C':
			case 'B':
			case 'Z':
			case 'I':
				err = jvmti->ForceEarlyReturnInt(thread, 0);
				break;
			case 'D':
				err = jvmti->ForceEarlyReturnDouble(thread, 0);
				break;
			case 'J':
				err = jvmti->ForceEarlyReturnLong(thread, 0);
				break;
			case 'F':
				err = jvmti->ForceEarlyReturnFloat(thread, 0);
				break;
			default:
				fatal_error("Unknown method return type %c", t);
			}
			if (err == JVMTI_ERROR_OPAQUE_FRAME) {
#ifdef PRINT_DEBUG
				printf(
						"Can't rollback because there is native code in the stack.\n");
#endif
				tinfo->ignored = 1;
				break;
			}
			check_jvmti_error(jvmti, err, "Unable to force return");
			//Now need to wake up that thread, then wait for it to return

			tinfo->inRollback = 1;
			jvmti->SetEventNotificationMode(JVMTI_ENABLE,
					JVMTI_EVENT_SINGLE_STEP, thread);
			err = jvmti->GetThreadState(thread, &threadState);
			check_jvmti_error(jvmti, err, "checking thread");

			err = jvmti->ResumeThread(thread);
			check_jvmti_error(jvmti, err, "resuming thread");
			while (tinfo->inRollback
					|| (threadState & JVMTI_THREAD_STATE_SUSPENDED) == 0) {
				//busy wait
				err = jvmti->GetThreadState(thread, &threadState);
				check_jvmti_error(jvmti, err, "checking thread");
			}
			continue; //This is a NEW method frame that wasn't checkpoitned because it was created after CP

		}
		stackFrame = tinfo->allFrames->next;
		tinfo->replayFrame = NULL;
		while(stackFrame)
		{
			if(stackFrame->next)
				stackFrame->next->prev = stackFrame;
			tinfo->replayFrame = stackFrame;
			stackFrame = stackFrame->next;
		}
		err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
				JVMTI_EVENT_SINGLE_STEP, thread);
		check_jvmti_error(jvmti, err, "Cannot set event notification");
	}
	//All threads are now totally unrolled, back to the first method.
#ifdef PRINT_DEBUG
			printf("Finished unrolling\n");
#endif
	//Now update the code of each method to have a signal to bring it to the spot where the rollback was called.
	for (ti = 0; ti < thread_count; ++ti) {
		jvmtiStackInfo *infop = &stack_info[ti];
		jthread thread = infop->thread;
#ifdef PRINT_DEBUG
		jvmti->GetThreadInfo(thread, &jtinfo);
		printf("%lld\n",jtinfo);
		printf("Updating code on thread %s\n", jtinfo.name);
		jvmti->Deallocate((unsigned char*) jtinfo.name);
#endif

		jvmti->GetThreadLocalStorage(thread, (void**) &tinfo);
		if(!tinfo || tinfo->ignored)
			continue;
		stackFrame = tinfo->allFrames;
		if(!stackFrame)
			continue;
		stackFrame = stackFrame->next;
		methodKey = 0;
		previousCallModifiers = 0;
		previousStackFrame = NULL;
		calldesc = NULL;
		while(stackFrame)
		{
#ifdef PRINT_DEBUG
			describeLocation(stackFrame->loc, stackFrame->meth);
			printf("<< rollback location? \n");
#endif
			if(stackFrame->ignored)
			{
				stackFrame = stackFrame->next;
				continue;
			}
			//We will NOT call rollback on stackframe entries here. We'll let that happen when we actually get back into
			//the method.
			if (!stackFrame->next) {
#ifdef PRINT_DEBUG
				describeLocation(stackFrame->loc, stackFrame->meth);
				printf("<< is last stack frame! \n");
#endif
				if (checkpointDepth >= 0) {
					/*
					 * We specified a depth, which means that rather than continue from the checkpoint
					 * statement, we'll continue execution right here, from the rollback statement
					 */
				}
				break; //dont bother sending up the root, which won't be replayed
			}
#ifdef PRINT_DEBUG
			describeLocation(stackFrame->loc, stackFrame->meth);
			printf("<< Place \n");
#endif

			if(previousStackFrame)
			{
				calldesc = mdesc;
#ifdef PRINT_DEBUG
				printf("Calling: %s\n",calldesc);
#endif
			}
			else
			{
				calldesc = NULL;
			}
#ifdef PRINT_DEBUG
			printf("Calling RollForwardTransfomer\n");
#endif
			jvmti->GetMethodName(stackFrame->meth, &mname, &mdesc, NULL);
			jvmti->GetMethodDeclaringClass(stackFrame->meth, &clazz);
			methodKey = jni->CallStaticIntMethod(gdata->rollForwardClass,
					gdata->rollForwardQueueMethod, clazz, jni->NewStringUTF(mname),
					jni->NewStringUTF(mdesc), jni->NewStringUTF(calldesc), (((previousCallModifiers & 0x0008) != 0)), thread, (jint) stackFrame->loc, methodKey, tinfo->nOwnedMonitorsAtCheckpoint, version);
			jvmti->GetMethodModifiers(stackFrame->meth,&previousCallModifiers);

			jvmti->Deallocate((unsigned char*) mname);
			if(previousStackFrame)
				jvmti->Deallocate((unsigned char*) calldesc);
//			printf("Key: %d\n",methodKey);
			previousStackFrame = stackFrame;
			stackFrame = stackFrame->next;
			if(!stackFrame)
				jvmti->Deallocate((unsigned char*) mdesc);
		}
		jni->CallStaticVoidMethod(gdata->rollForwardClass,gdata->rollForwardGoMethod);
	}
#ifdef PRINT_DEBUG
	printf("Done\n");
#endif
	err = gdata->jvmti->Deallocate((unsigned char*) stack_info);
}
JNIEXPORT static void JNICALL checkpointStack(JNIEnv *env, jclass klass,
		jint v, jboolean ignoreCurrent) {
	jvmtiError err;
	shouldCheckpoint = true;
	version = v;
#ifdef PRINT_DEBUG
	printf("Checkpoint start\n");
#endif
	err = gdata->jvmti->GetCurrentThread(&callingThread);
	check_jvmti_error(gdata->jvmti,err,"Unable to get cur thread");
	callingThread = env->NewGlobalRef(callingThread);
	ignoreCurrentThread = ignoreCurrent;
}
JNIEXPORT static void JNICALL rollbackStack(JNIEnv *env, jclass klass, jint v, jboolean ignoreCurrent) {
	jvmtiError err;
	shouldCheckpoint = false;
	version = v;
#ifdef PRINT_DEBUG
	printf("Rollback start\n");
#endif
	err = gdata->jvmti->GetCurrentThread(&callingThread);
	check_jvmti_error(gdata->jvmti, err, "Unable to get cur thread");
	callingThread = env->NewGlobalRef(callingThread);
	ignoreCurrentThread = ignoreCurrent;
}
JNIEXPORT static jobject JNICALL getInitializedClasses(JNIEnv *env, jclass klass) {
	jvmtiError err;
	jint count;
	jclass* classes;
	jobjectArray ret;
	gdata->jvmti->GetLoadedClasses(&count,&classes);
	int i;
	jboolean isIface;
	jint status;
	int n = 0;
	for(i=0;i<count;i++)
	{
		gdata->jvmti->GetClassStatus(classes[i], &status);
		if ((status & 0x0004) != 0
				&& (status & 0x0020) == 0
				&& (status & 0x0010) == 0) //initialized, non-array, non-primitive
						{
			n++;
		}
		else
			classes[i] = NULL;
	}
	jclass ClassClass = env->FindClass("java/lang/Class");
	if(ClassClass == NULL)
		fatal_error("Unable to find 'field' class!");
	ret = env->NewObjectArray(n,ClassClass,NULL);
	n = 0;
	for(int i = 0; i<count;i++)
	{
		if(classes[i] != NULL)
		{
			env->SetObjectArrayElement(ret, n, classes[i]);
			n++;
		}
	}
	gdata->jvmti->Deallocate((unsigned char*) classes);
	return (jobject) ret;
}
JNIEXPORT static jobject JNICALL getUnInitializedClasses(JNIEnv *env, jclass klass) {
	jvmtiError err;
	jint count;
	jclass* classes;
	jobjectArray ret;
	gdata->jvmti->GetLoadedClasses(&count,&classes);
	int i;
	jboolean isIface;
	jint status;
	int n = 0;
	for(i=0;i<count;i++)
	{
		gdata->jvmti->GetClassStatus(classes[i], &status);
		if ((status & 0x0004) == 0
				&& (status & 0x0020) == 0) //not-initialized, non-array
						{
			n++;
		}
		else
			classes[i] = NULL;
	}
	jclass ClassClass = env->FindClass("java/lang/Class");
	if(ClassClass == NULL)
		fatal_error("Unable to find 'field' class!");
	ret = env->NewObjectArray(n,ClassClass,NULL);
	n = 0;
	for(int i = 0; i<count;i++)
	{
		if(classes[i] != NULL)
		{
			env->SetObjectArrayElement(ret, n, classes[i]);
			n++;
		}
	}
	gdata->jvmti->Deallocate((unsigned char*) classes);
	return (jobject) ret;
}

/*
 * Implementation of _setTag JNI function.
 */
JNIEXPORT static void JNICALL setObjExpression(JNIEnv *env, jclass klass,
		jobject o, jobject expr) {
	if (gdata->vmDead) {
		return;
	}
	if(!o)
	{
		return;
	}
	jvmtiError error;
	jlong tag;
	if (expr) {
		//First see if there's already something set here
		error =gdata->jvmti->GetTag(o,&tag);
		if(error == JVMTI_ERROR_WRONG_PHASE)
			return;//JVM shutting down!
		check_jvmti_error(gdata->jvmti, error, "Cannot get object tag");
		if(tag)
		{
			//Delete reference to old thing
			env->DeleteGlobalRef((jobject)(ptrdiff_t) tag);
		}
		//Set the tag, make a new global reference to it
		error = gdata->jvmti->SetTag(o, (jlong) (ptrdiff_t) (void*) env->NewGlobalRef(expr));
		check_jvmti_error(gdata->jvmti, error, "Cannot set object tag");

	} else {
		error = gdata->jvmti->SetTag(o, 0);
	}
	if(error == JVMTI_ERROR_WRONG_PHASE)
	return;
	check_jvmti_error(gdata->jvmti, error, "Cannot set object tag");
}
/*
 * Implementation of _getTag JNI function
 */
JNIEXPORT static jobject JNICALL getObjExpression(JNIEnv *env, jclass klass,
		jobject o) {
	if (gdata->vmDead) {
		return NULL;
	}
	jvmtiError error;
	jlong tag;
	error = gdata->jvmti->GetTag(o, &tag);
	if(error == JVMTI_ERROR_WRONG_PHASE)
	return NULL;
	check_jvmti_error(gdata->jvmti, error, "Cannot get object tag");
	if(tag)
	{
		return (jobject) (ptrdiff_t) tag;
	}
	return NULL;
}

/*
 * Since we create a global reference to whatever we tag an object with, we need to clean this up
 * when the tagged object is garbage collected - otherwise tags wouldn't ever be garbage collected.
 * When a tagged object is GC'ed, we add its tag to a deletion queue. We will process the queue at the next GC.
 */
static void JNICALL
cbObjectFree(jvmtiEnv *jvmti_env, jlong tag) {
	if (gdata->vmDead) {
		return;
	}
	jvmtiError error;
	if (tag) {
		error = gdata->jvmti->RawMonitorEnter(deleteQueueLock);
		check_jvmti_error(jvmti_env, error, "raw monitor enter");
		DeleteQueue* tmp = deleteQueue;
		deleteQueue = new DeleteQueue();
		deleteQueue->next = tmp;
		deleteQueue->obj = (jobject) (ptrdiff_t) tag;
		error = gdata->jvmti->RawMonitorExit(deleteQueueLock);
		check_jvmti_error(jvmti_env, error, "raw monitor exit");
	}
}

static jrawMonitorID gcLock;
static int gc_count;

/*
 * Garbage collection worker thread that will asynchronously free tags
 */
static void JNICALL
gcWorker(jvmtiEnv* jvmti, JNIEnv* jni, void *p)
{
	jvmtiError err;
	for (;;) {
		err = jvmti->RawMonitorEnter(gcLock);
		check_jvmti_error(jvmti, err, "raw monitor enter");
		while (gc_count == 0) {
			err = jvmti->RawMonitorWait(gcLock, 0);
			if (err != JVMTI_ERROR_NONE) {
				err = jvmti->RawMonitorExit(gcLock);
				check_jvmti_error(jvmti, err, "raw monitor wait");
				return;
			}
		}
		gc_count = 0;

		err = jvmti->RawMonitorExit(gcLock);
		check_jvmti_error(jvmti, err, "raw monitor exit");

		DeleteQueue * tmp;
		while(deleteQueue)
		{
			err = jvmti->RawMonitorEnter(deleteQueueLock);
			check_jvmti_error(jvmti, err, "raw monitor enter");

			tmp = deleteQueue;
			deleteQueue = deleteQueue->next;
			err = jvmti->RawMonitorExit(deleteQueueLock);
			check_jvmti_error(jvmti, err, "raw monitor exit");
			jni->DeleteGlobalRef(tmp->obj);

			delete tmp;
		}
	}
}
static void JNICALL
stackHacker(jvmtiEnv* jvmti, JNIEnv* jni, void *p)
{
	jvmtiError err;
	jthread thisThread;
	jint threadState;
	jvmtiThreadInfo tinfo;
	jvmtiThreadGroupInfo tginfo;

	err = jvmti->GetCurrentThread(&thisThread);
	check_jvmti_error(jvmti, err, "Cant get this thread");
	jni->MonitorEnter(stackLock);
	for (;;) {
//		while (gc_count == 0) {

			// Wait for a thread to request a checkpoint/rollback
			jni->CallVoidMethod(stackLock,gdata->wait);
//			err = jvmti->RawMonitorWait(stackLock, 0);
//			if (err != JVMTI_ERROR_NONE) {
//				err = jvmti->RawMonitorExit(stackLock);
//				printf("Stack hacker gave up\n");
//				check_jvmti_error(jvmti, err, "raw monitor wait");
//				return;
//			}

//		}
		gc_count = 0;


		//Before getting threads, must make sure none of them are in the middle of a C/R!

		// jni->MonitorEnter(gdata->lockPolicy);
		//Suspend all other threads
		jthread * threads;
		jint nThreads;

		int i=0;
		int k=0;
		err = jvmti->GetAllThreads(&nThreads,&threads);
		check_jvmti_error(jvmti, err, "getting threads");

		jvmtiError errs[nThreads-1];
		jthread  threadsToSuspend[nThreads-1];
		for(i = 0; i < nThreads; i++)
		{
			if(!jni->IsSameObject(threads[i],thisThread))
			{
				jni->CallVoidMethod(gdata->lockPolicy, gdata->lockThread, threads[i]);
				threadsToSuspend[k] = threads[i];
				k++;
			}
		}
		jvmti->SuspendThreadList(nThreads-1,threadsToSuspend,errs);
		for(i = 0; i < nThreads -1;i++)
		{
			check_jvmti_error(jvmti,errs[i],"Suspending thread");
		}


    // Do the checkpoint/rollback
		if(shouldCheckpoint)
			doCheckpoint(jvmti, jni);
		else
			doRollback(jvmti, jni);

//		printf("Resuming\n");
    // Resume all threads
		if(!shouldCheckpoint && ignoreCurrentThread)
		{
			//We are rollingback and didn't want to roll back the current thread.
			//It's stuck in busy waiting unless we release it!
			err = jvmti->ForceEarlyReturnVoid(callingThread);
			check_jvmti_error(jvmti,err,"Unable to resume caller thread");
		}
		jvmti->ResumeThreadList(nThreads-1,threadsToSuspend,errs);
		for(i = 0; i < nThreads -1;i++)
		{
			jni->CallVoidMethod(gdata->lockPolicy, gdata->unlockThread, threads[i]);
			check_jvmti_error(jvmti,errs[i],"Suspending thread");
		}
		// jni->MonitorExit(gdata->lockPolicy);

		if(shouldCheckpoint)
		{
			//Release our big lock
			jni->CallVoidMethod(stackLock,gdata->notify);
		}
		if(callingThread)
		{
			jni->DeleteGlobalRef(callingThread);
			callingThread = NULL;
		}
    // Notify the thread that started the checkpoint/rollback that it's done
//		err = gdata->jvmti->RawMonitorNotify(stackLock);
//		check_jvmti_error(gdata->jvmti, err, "raw monitor notify");
	}
//	err = gdata->jvmti->RawMonitorExit(stackLock);
//	check_jvmti_error(gdata->jvmti, err, "raw monitor exit");
	jni->MonitorExit(stackLock);
}

/*
 * Callback to notify us when a GC finishes. When a GC finishes,
 * we wake up our GC thread and free all tags that need to be freed.
 */
static void JNICALL
gc_finish(jvmtiEnv* jvmti_env)
{
	jvmtiError err;
	err = gdata->jvmti->RawMonitorEnter(gcLock);
	check_jvmti_error(gdata->jvmti, err, "raw monitor enter");
	gc_count++;
	err = gdata->jvmti->RawMonitorNotify(gcLock);
	check_jvmti_error(gdata->jvmti, err, "raw monitor notify");
	err = gdata->jvmti->RawMonitorExit(gcLock);
	check_jvmti_error(gdata->jvmti, err, "raw monitor exit");
}
static jvalue* getLV(int offset, int depth, jvmtiParamTypes expected)
{
	jvmtiError er;
	ThreadLocalInfo *ti;
	CheckpointedStackFrame * stackFrame;
	StackEntry *e;
	int origDepth;
	origDepth = depth;
	er = gdata->jvmti->GetThreadLocalStorage(NULL, (void**) &ti);
	check_jvmti_error(gdata->jvmti, er, "Unable to get threadlocal");
	stackFrame = ti->replayFrame;
#ifdef PRINT_DEBUG
	describeLocation(stackFrame->loc, stackFrame->meth);
	printf("Get arg %d in %d d=%d\n",expected, offset, depth);
#endif
	while(depth > 0)
	{
		stackFrame= stackFrame->prev;
		depth--;
	}
#ifdef PRINT_DEBUG
	describeLocation(stackFrame->loc, stackFrame->meth);
	printf("Get arg %d in %d d=%d\n",expected, offset, origDepth);
#endif

	e = stackFrame->entries;
	while(e)
	{
		if(e->slot == offset)
			break;
		e=e->next;
	}
	if(!e)
	{
		return NULL; //the caller code will ask for the fake LVs that are used for stack capturing in checkpoint, and they wont be saved
	}
	if(e->slot != offset || (e->type != expected && e->type != JVMTI_TYPE_JVALUE))
	{
		printStrackTrace();
		fatal_error("Asking for a var at offset %d but couldn't find it depth %d  type %d\n",offset,depth,expected);
	}
	return &(e->val);
}
static jvalue popStackEl(jvmtiParamTypes expected)
{
	jvmtiError er;
	ThreadLocalInfo *ti;
	CheckpointedStackFrame * stackFrame;
	StackEntry *e;
	er = gdata->jvmti->GetThreadLocalStorage(NULL, (void**) &ti);
	check_jvmti_error(gdata->jvmti, er, "Unable to get threadlocal");
	stackFrame = ti->replayFrame;
	describeLocation(stackFrame->loc, stackFrame->meth);
	printf("Get stack obj\n");
	if (!stackFrame->operandStack)
		fatal_error("Nothing found on stack!\n");

	e = stackFrame->operandStack->next;
//	printf("..%lld\n", e);
	if (!e)
		fatal_error("Nothing found on stack!\n");
	if (e->type != JVMTI_TYPE_JOBJECT)
		fatal_error("Asking for a stack obj but found %d\n", e->type);
	stackFrame->operandStack = e;
	return e->val;
}
static jobject JNICALL getLVObject(JNIEnv *jni, jclass klass, jint offset, jint depth)
{
	jvalue* ret = getLV(offset,depth,JVMTI_TYPE_JOBJECT);
	if(ret != NULL)
	{
//		printf("Ret %lld\n",ret->l);
		return ret->l;
	}
	else
		return NULL;
}
static jint JNICALL getLVInt(JNIEnv *jni, jclass klass, jint offset, jint depth)
{
	jvalue* ret = getLV(offset,depth,JVMTI_TYPE_JINT);
	if(ret != NULL)
		return ret->i;
	else
		return 0;
}
static jfloat JNICALL getLVFloat(JNIEnv *jni, jclass klass, jint offset, jint depth)
{
	jvalue* ret = getLV(offset,depth,JVMTI_TYPE_JFLOAT);
	if(ret != NULL)
		return ret->f;
	else
		return 0;
}
static jlong JNICALL getLVLong(JNIEnv *jni, jclass klass, jint offset, jint depth)
{
	jvalue* ret = getLV(offset,depth,JVMTI_TYPE_JLONG);
	if(ret != NULL)
		return ret->j;
	else
		return 0;
}
static jdouble JNICALL getLVDouble(JNIEnv *jni, jclass klass, jint offset, jint depth)
{
	jvalue * ret = getLV(offset,depth,JVMTI_TYPE_JDOUBLE);
	if(ret != NULL)
		return ret->d;
	else
		return 0;
}
static jobject JNICALL getStackObject(JNIEnv *jni, jclass klass)
{
	return popStackEl(JVMTI_TYPE_JOBJECT).l;
}
static jint JNICALL getStackInt(JNIEnv *jni, jclass klass)
{
	return popStackEl(JVMTI_TYPE_JOBJECT).i;
}
static jfloat JNICALL getStackFloat(JNIEnv *jni, jclass klass)
{
	return popStackEl(JVMTI_TYPE_JOBJECT).f;
}
static jlong JNICALL getStackLong(JNIEnv *jni, jclass klass)
{
	return popStackEl(JVMTI_TYPE_JOBJECT).j;
}
static jdouble JNICALL getStackDouble(JNIEnv *jni, jclass klass)
{
	return popStackEl(JVMTI_TYPE_JOBJECT).d;
}
static jlong JNICALL getObjectSize(JNIEnv *jni, jclass klass,
        jobject object)
{
	jlong ret;
	jvmtiError er;
	if(!object)
		return 0L;
	er = gdata->jvmti->GetObjectSize(object,&ret);
	check_jvmti_error(gdata->jvmti,er,"Can't get object size");
	return ret;
}
static void JNICALL captureStack(JNIEnv *jni, jclass klass) {
	jvmtiError er;
	ThreadLocalInfo *ti;
	CheckpointedStackFrame * frame;
	jvmtiFrameInfo fb[1];
	jint fi;
	er = gdata->jvmti->GetThreadLocalStorage(NULL, (void**) &ti);
	int i;
	int location;
	bool inStackLoad = 1;
	StackEntry *e;
	unsigned char op;
	frame = ti->curFrame;
	if (frame->next) {
		frame = frame->next;

		while (frame->ignored)
			frame = frame->next;
		ti->curFrame = frame;
		er = gdata->jvmti->GetStackTrace(NULL, 1, 1, fb, &fi);
		check_jvmti_error(gdata->jvmti, er, "Unable to get cur loc");

		//Make sure we didn't get messed up somehow
		if (frame->meth != fb[0].method) {
			printf("DIVERGED!!!\n");
			describeLocation(frame->loc, frame->meth);
			printf("< %d Loc?\n", frame->ignored);
			describeLocation(fb[0].location, fb[0].method);
			printf("<<actual\n");
		}
		location = fb[0].location;
		if (frame->nBytecodes == 0) {
			er = gdata->jvmti->GetBytecodes(frame->meth, &(frame->nBytecodes),
					&(frame->bytecodes));
			check_jvmti_error(gdata->jvmti, er, "Cannot get bytecodes");
		}
		location+=3;

		if (frame->bytecodes[location] != 0) {
//			describeLocation(frame->loc, frame->meth);
//			printf("<<pulling stack togther@loc%d \n",location);
			frame->operandStack = new StackEntry();
			frame->operandStack->next = NULL;
			frame->operandStack->prev = NULL;

			while (inStackLoad) {
				location++;
				op = frame->bytecodes[location];
//				printf("OP is %d\n", op);

				//Process remaining vars
				switch (op) {
				case 21:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JINT;
					e->next = frame->operandStack->next;
					e->slot = (int) frame->bytecodes[location + 1];
					frame->operandStack->next = e;
					location++;
					break;
				case 22:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JLONG;
					e->next = frame->operandStack->next;
					e->slot = (int) frame->bytecodes[location + 1];
					frame->operandStack->next = e;
					location++;
					break;
				case 23:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JFLOAT;
					e->next = frame->operandStack->next;
					e->slot = (int) frame->bytecodes[location + 1];
					frame->operandStack->next = e;
					location++;
					break;
				case 24:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JDOUBLE;
					e->next = frame->operandStack->next;
					e->slot = (int) frame->bytecodes[location + 1];
					frame->operandStack->next = e;
					location++;
					break;
				case 25:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JOBJECT;
					e->next = frame->operandStack->next;
					e->slot = (int) frame->bytecodes[location + 1];
					frame->operandStack->next = e;
					location++;
					break;
				case 26:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JINT;
					e->next = frame->operandStack->next;
					e->slot = 0;
					frame->operandStack->next = e;
					break;
				case 27:
					e = new StackEntry();
					e->slot = 1;
					e->type = JVMTI_TYPE_JINT;
					e->next = frame->operandStack->next;
					frame->operandStack->next = e;
					break;
				case 28:
					e = new StackEntry();
					e->slot = 2;
					e->type = JVMTI_TYPE_JINT;
					e->next = frame->operandStack->next;
					frame->operandStack->next = e;
					break;
				case 29:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JINT;
					e->next = frame->operandStack->next;
					e->slot = 3;
					frame->operandStack->next = e;
					break;
				case 30:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JLONG;
					e->next = frame->operandStack->next;
					e->slot = 0;
					frame->operandStack->next = e;
					break;
				case 31:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JLONG;
					e->next = frame->operandStack->next;
					e->slot = 1;
					frame->operandStack->next = e;
					break;
				case 32:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JLONG;
					e->next = frame->operandStack->next;
					e->slot = 2;
					frame->operandStack->next = e;
					break;
				case 33:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JLONG;
					e->next = frame->operandStack->next;
					e->slot = 3;
					frame->operandStack->next = e;
					break;
				case 34:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JFLOAT;
					e->next = frame->operandStack->next;
					e->slot = 0;
					frame->operandStack->next = e;
					break;
				case 35:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JFLOAT;
					e->next = frame->operandStack->next;
					e->slot = 1;
					frame->operandStack->next = e;
					break;
				case 36:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JFLOAT;
					e->next = frame->operandStack->next;
					e->slot = 2;
					frame->operandStack->next = e;
					break;
				case 37:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JFLOAT;
					e->next = frame->operandStack->next;
					e->slot = 3;
					frame->operandStack->next = e;
					break;
				case 38:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JDOUBLE;
					e->next = frame->operandStack->next;
					e->slot = 0;
					frame->operandStack->next = e;
					break;
				case 39:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JDOUBLE;
					e->next = frame->operandStack->next;
					e->slot = 1;
					frame->operandStack->next = e;
					break;
				case 40:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JDOUBLE;
					e->next = frame->operandStack->next;
					e->slot = 2;
					frame->operandStack->next = e;
					break;
				case 41:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JDOUBLE;
					e->next = frame->operandStack->next;
					e->slot = 3;
					frame->operandStack->next = e;
					break;
				case 42:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JOBJECT;
					e->next = frame->operandStack->next;
					e->slot = 0;
					frame->operandStack->next = e;
					break;
				case 43:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JOBJECT;
					e->next = frame->operandStack->next;
					e->slot = 1;
					frame->operandStack->next = e;
					break;
				case 44:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JOBJECT;
					e->next = frame->operandStack->next;
					e->slot = 2;
					frame->operandStack->next = e;
					break;
				case 45:
					e = new StackEntry();
					e->type = JVMTI_TYPE_JOBJECT;
					e->next = frame->operandStack->next;
					e->slot = 3;
					frame->operandStack->next = e;
					break;
				default:
					/*
					 * After the NOP, then after all of the X_STORE_N instructions, we can
					 * retrieve all of the stack values.
					 */
					e = frame->operandStack->next;
					while (e) {
						if (e->type == JVMTI_TYPE_JOBJECT) {
							er = gdata->jvmti->GetLocalObject(NULL, 1, e->slot,
									&(e->val.l));
							check_jvmti_error(gdata->jvmti, er,
									"Unable to get local var");
							if (e->val.l) {
								e->val.l = jni->NewGlobalRef(e->val.l);
								jni->CallStaticVoidMethod(
										gdata->objectWrapperKlass,
										gdata->checkpointMethod, e->val.l,
										version);
								if (jni->ExceptionCheck()) {
									printf(
											"ERROR: Exception calling checkpoint\n");
								}
							}
						} else if (e->type == JVMTI_TYPE_JFLOAT) {
							er = gdata->jvmti->GetLocalFloat(NULL, 1, e->slot,
									&(e->val.f));
							check_jvmti_error(gdata->jvmti, er,
									"Unable to get local var");
						} else if (e->type == JVMTI_TYPE_JDOUBLE) {
							er = gdata->jvmti->GetLocalDouble(NULL, 1, e->slot,
									&(e->val.d));
							check_jvmti_error(gdata->jvmti, er,
									"Unable to get local var");
						} else if (e->type == JVMTI_TYPE_JLONG) {
							er = gdata->jvmti->GetLocalLong(NULL, 1, e->slot,
									&(e->val.j));
							check_jvmti_error(gdata->jvmti, er,
									"Unable to get local var");
						} else if (e->type == JVMTI_TYPE_JINT) {
							er = gdata->jvmti->GetLocalInt(NULL, 1, e->slot,
									&(e->val.i));
							check_jvmti_error(gdata->jvmti, er,
									"Unable to get local var");
						}
						e = e->next;
					}
					inStackLoad = 0;
					break;

				}
			}
		}
	} else {
		jvmtiFrameInfo frames[4];
		jint nframes;
		gdata->jvmti->GetStackTrace(NULL,0,4,frames,&nframes);
		for(i =0;i<nframes;i++)
		{
		describeLocation(frames[i].location,frames[i].method);
		printf("<<<\n");
		}

		fatal_error("Ran out of stackframes to record!\n");
	}
}
static void JNICALL advanceFrame(JNIEnv *jni, jclass klass)
{
	jvmtiError er;
	ThreadLocalInfo *ti;
	CheckpointedStackFrame * stackFrame;
	er = gdata->jvmti->GetThreadLocalStorage(NULL,(void**)&ti);
	check_jvmti_error(gdata->jvmti,er,"Unable to get threadlocal");
	if(!ti)
	{
		printf("Advancing frame, but no threadlocal!\n");
		printStrackTrace();
		fatal_error("Advancing frame, but no threadlocal!\n");
	}
	stackFrame = ti->replayFrame;
#ifdef PRINT_DEBUG
	if(stackFrame)
		describeLocation(stackFrame->loc,stackFrame->meth);

	printf("<<<PREVIOUS frame\n");
#endif
	ti->replayFrame = ti->replayFrame->prev;
#ifdef PRINT_DEBUG
	if(ti->replayFrame)
		describeLocation(ti->replayFrame->loc,ti->replayFrame->meth);
	printf("<<<NEW frame\n");
#endif
	ti->replayFrame->next = NULL;
	freeStackFrame(jni,stackFrame);
	return;
}
static jobject JNICALL getMonitorIdx(JNIEnv *jni, jclass klass, jint idx)
{
	jvmtiError er;
	ThreadLocalInfo *ti;
	er = gdata->jvmti->GetThreadLocalStorage(NULL, (void**) &ti);
	check_jvmti_error(gdata->jvmti, er, "Unable to get threadlocal");
	return ti->ownedMonitorsAtCheckpoint[idx];
}
/*
 * Create a new java.lang.Thread
 */
static jthread alloc_thread(JNIEnv *env) {
	jclass thrClass;
	jmethodID cid;
	jthread res;

	thrClass = env->FindClass("java/lang/Thread");
	if (thrClass == NULL) {
		fatal_error("Cannot find Thread class\n");
	}
	cid = env->GetMethodID(thrClass, "<init>", "()V");
	if (cid == NULL) {
		fatal_error("Cannot find Thread constructor method\n");
	}
	res = env->NewObject(thrClass, cid);
	if (res == NULL) {
		fatal_error("Cannot create new Thread object\n");
	}
	return res;
}

/*
 * Callback we get when the JVM is initialized. We use this time to setup our GC thread
 */
static void JNICALL callbackVMInit(jvmtiEnv * jvmti, JNIEnv * env, jthread thread)
{
  env->CallStaticVoidMethod(gdata->taggerClass,gdata->init);
	jfieldID field;
	field = env->GetStaticFieldID(gdata->taggerClass,"lockPolicy","Lnet/jonbell/crij/runtime/lock/LockPolicy;");
	//field = env->GetStaticFieldID(gdata->taggerClass,"lockPolicy","Lnet/jonbell/crij/runtime/lock/VersionCASLockPolicy;");
	if(field == NULL)
	{
		fatal_error("Couldn't get field");
	}
	gdata->lockPolicy = env->GetStaticObjectField(gdata->taggerClass,field);
	if(gdata->lockPolicy == NULL)
	{
		fatal_error("ERROR: Unable to get global lock");
	}
	gdata->lockPolicy = env->NewGlobalRef(gdata->lockPolicy);

	jvmtiError err;
	err = jvmti->RunAgentThread(alloc_thread(env), &gcWorker, NULL,
			JVMTI_THREAD_MAX_PRIORITY);
	check_jvmti_error(jvmti, err, "Unable to run agent cleanup thread");

	err = jvmti->RunAgentThread(alloc_thread(env), &stackHacker, NULL,
				JVMTI_THREAD_MAX_PRIORITY);
		check_jvmti_error(jvmti, err, "Unable to run stack hacker thread");

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

		static JNINativeMethod continuationRegistry[12] = {
				{"getLVObject","(II)Ljava/lang/Object;", (void*) &getLVObject},
				{"getLVInt","(II)I", (void*) &getLVInt},
				{"getLVLong","(II)J", (void*) &getLVLong},
				{"getLVFloat","(II)F", (void*) &getLVFloat},
				{"getLVDouble","(II)D", (void*) &getLVDouble},
				{"getStackObject","()Ljava/lang/Object;", (void*) &getStackObject},
				{"getStackInt","()I", (void*) &getStackInt},
				{"getStackFloat","()F", (void*) &getStackFloat},
				{"getStackLong","()J", (void*) &getStackLong},
				{"getStackDouble","()D", (void*) &getStackDouble},
				{"advanceFrame","()V", (void*) &advanceFrame},
				{"getOwnedMonitor","(I)Ljava/lang/Object;",(void*)&getMonitorIdx}
		};
		klass = env->FindClass("net/jonbell/crij/instrument/RollForwardTransformer");
			rc = env->RegisterNatives(klass, continuationRegistry,12);
			if (rc != 0) {
						fatal_error(
								"ERROR: JNI: Cannot register natives for RollForward\n");
					}

		static JNINativeMethod registry[8] = {
				{ "_setTag", "(Ljava/lang/Object;Ljava/lang/Object;)V", (void*) &setObjExpression },
				{ "_getTag", "(Ljava/lang/Object;)Ljava/lang/Object;", (void*) &getObjExpression },
				{ "_checkpointStack", "(IZ)V", (void*) &checkpointStack },
				{ "_rollbackStack", "(IZ)V", (void*) &rollbackStack },
				{ "_getInitializedClasses", "()Ljava/lang/Object;", (void*) &getInitializedClasses },
				{ "_getUnInitializedClasses", "()Ljava/lang/Object;", (void*) &getUnInitializedClasses },
				{ "captureStack","()V", (void*) &captureStack },
				{ "getObjectSize","(Ljava/lang/Object;)J", (void*) &getObjectSize }};
		/* Register Natives for class whose methods we use */
		klass = env->FindClass("net/jonbell/crij/runtime/Tagger");
		if (klass == NULL) {
			fatal_error(
					"ERROR: JNI: Cannot find Tagger with FindClass\n");
		}
		rc = env->RegisterNatives(klass, registry, 8);
		if (rc != 0) {
			fatal_error(
					"ERROR: JNI: Cannot register natives for Tagger\n");
		}
		gdata->taggerClass = (jclass) env->NewGlobalRef(klass);
		/* Engage calls. */
		field = env->GetStaticFieldID(klass, "engaged", "I");
		if (field == NULL) {
			fatal_error("ERROR: JNI: Cannot get field\n"
			);
		}
		env->SetStaticIntField(klass, field, 1);

		field = env->GetStaticFieldID(klass,"jvmtiLock","Ljava/lang/Object;");
		if (field == NULL) {
					fatal_error("ERROR: JNI: Cannot get field\n"
					);
				}
		stackLock = env->NewGlobalRef(env->GetStaticObjectField(klass,field));
		gdata->classClass = env->FindClass("java/lang/Class");
		if (gdata->classClass == NULL) {
				fatal_error(
						"ERROR: JNI: Cannot find Class with FindClass\n");
			}
		gdata->classClass = (jclass) env->NewGlobalRef(gdata->classClass);

		gdata->objectWrapperKlass = env->FindClass(
				"net/jonbell/crij/runtime/wrapper/ObjectWrapper");
		if (gdata->objectWrapperKlass == NULL) {
			fatal_error("ERROR: JNI: Cannot find Tagger with FindClass\n");
		}
		gdata->objectWrapperKlass = (jclass) env->NewGlobalRef(
				gdata->objectWrapperKlass);
		gdata->checkpointMethod = env->GetStaticMethodID(
				gdata->objectWrapperKlass, "propagateCheckpoint",
				"(Ljava/lang/Object;I)V");
		if (gdata->checkpointMethod == NULL) {
			fatal_error("ERROR: JNI: Cannot find Tagger with FindClass\n");
		}
		gdata->rollbackMethod = env->GetStaticMethodID(
				gdata->objectWrapperKlass, "propagateRollback",
				"(Ljava/lang/Object;I)V");
		if (gdata->rollbackMethod == NULL) {
			fatal_error("ERROR: JNI: Cannot find Tagger with FindClass\n");
		}
		gdata->staticFieldWalkerClass = (jclass) env->FindClass(
				"net/jonbell/crij/runtime/StaticFieldWalker");
		if (gdata->staticFieldWalkerClass == NULL) {
			fatal_error(
					"ERROR: JNI: Cannot find CheckpointRollbackAgent with FindClass\n");
		}
		gdata->staticFieldWalkerClass = (jclass) env->NewGlobalRef(
				gdata->staticFieldWalkerClass);
		gdata->traverseStaticFieldsMethod = env->GetStaticMethodID(
				gdata->staticFieldWalkerClass, "traverseStaticFields", "(ZI)V");
		if (gdata->traverseStaticFieldsMethod == NULL) {
			fatal_error("ERROR: JNI: Cannot find Tagger with FindClass\n");
		}

		gdata->init = env->GetStaticMethodID(klass,"init","()V");

		klass = env->FindClass("net/jonbell/crij/runtime/lock/LockPolicy");
		gdata->lockThread = env->GetMethodID(klass,"lockThread","(Ljava/lang/Thread;)V");
		gdata->unlockThread = env->GetMethodID(klass,"unlockThread","(Ljava/lang/Thread;)V");

		klass = env->FindClass("java/lang/Object");
		gdata->wait = env->GetMethodID(klass,"wait","()V");
		gdata->notify = env->GetMethodID(klass,"notify","()V");

		gdata->rollForwardClass = (jclass) env->NewGlobalRef(env->FindClass("net/jonbell/crij/instrument/RollForwardTransformer"));
		gdata->rollForwardQueueMethod = env->GetStaticMethodID(gdata->rollForwardClass,"transformForRollForward","(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/Thread;IIII)I");
		gdata->rollForwardGoMethod = env->GetStaticMethodID(gdata->rollForwardClass,"doTransform","()V");



		/* Indicate VM has started */
		gdata->vm_is_started = JNI_TRUE;

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
	capa.can_signal_thread = 1;
	capa.can_generate_object_free_events = 1;
	capa.can_tag_objects = 1;
	capa.can_generate_garbage_collection_events = 1;
//	capa.can_set_native_method_prefix = 1;
	if(withStack)
	{
		gdata->withStackSupport = true;
		capa.can_access_local_variables = 1;
//		capa.can_generate_frame_pop_events = 1;
		capa.can_suspend = 1;
		capa.can_generate_single_step_events = 1;
		capa.can_get_bytecodes = 1;
		capa.can_force_early_return = 1;
		capa.can_pop_frame = 1;
		capa.can_get_owned_monitor_info = 1;
	}
	error = jvmti->AddCapabilities(&capa);
	check_jvmti_error(jvmti, error,
			"Unable to get necessary JVMTI capabilities.");

	//Register callbacks
	(void) memset(&callbacks, 0, sizeof(callbacks));
	callbacks.VMInit = &callbackVMInit;
	callbacks.VMDeath = &callbackVMDeath;
	callbacks.VMStart = &cbVMStart;
	callbacks.ObjectFree = &cbObjectFree;
	callbacks.GarbageCollectionFinish = &gc_finish;
//	callbacks.FramePop = &frame_pop;
	callbacks.SingleStep = &step;

	error = jvmti->SetEventCallbacks(&callbacks, (jint) sizeof(callbacks));
	check_jvmti_error(jvmti, error, "Cannot set jvmti callbacks");

//	error = jvmti->SetNativeMethodPrefix("$$CRIJNATIVEWRAP$$");
//	check_jvmti_error(jvmti, error, "Cannot set native prefix");

	//Register for events
	error = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_START,
			(jthread) NULL);
	check_jvmti_error(jvmti, error, "Cannot set event notification");
	error = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
			JVMTI_EVENT_GARBAGE_COLLECTION_FINISH, (jthread) NULL);
	check_jvmti_error(jvmti, error, "Cannot set event notification");
	error = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT,
			(jthread) NULL);
	check_jvmti_error(jvmti, error, "Cannot set event notification");
	error = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_DEATH,
			(jthread) NULL);
	check_jvmti_error(jvmti, error, "Cannot set event notification");
	error = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
			JVMTI_EVENT_OBJECT_FREE, (jthread) NULL);
	check_jvmti_error(jvmti, error, "Cannot set event notification");
	if(withStack){
		jvmtiJlocationFormat fmt;
		error = jvmti->GetJLocationFormat(&fmt);
		check_jvmti_error(jvmti, error, "Cannot get jlocatoin format");
		if(fmt != JVMTI_JLOCATION_JVMBCI)
			fatal_error("JVMTI JLocation format is not bytecode index!\n");
	}

	//Set up a few locks
	error = jvmti->CreateRawMonitor("agent data", &(gdata->lock));
	check_jvmti_error(jvmti, error, "Cannot create raw monitor");

	error = jvmti->CreateRawMonitor("agent gc lock", &(gcLock));
	check_jvmti_error(jvmti, error, "Cannot create raw monitor");

	error = jvmti->CreateRawMonitor("agent stack pop lock", &(stackPopLock));
	check_jvmti_error(jvmti, error, "Cannot create raw monitor");


	stackLock = NULL;

	error = jvmti->CreateRawMonitor("agent gc queue", &(deleteQueueLock));
	check_jvmti_error(jvmti, error, "Cannot create raw monitor");

	return JNI_OK;
}
