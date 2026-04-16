UNAME := $(shell uname)
CCFLAGS = -I${JAVA_HOME}/include -c -fPIC -fpermissive -g -O0 #-std=c++11 -g -O0
ifeq ($(UNAME), Linux)
	CCFLAGS += -I${JAVA_HOME}/include
	CCFLAGS += -I${JAVA_HOME}/include/linux
	LINKFLAGS = -z defs -static-libgcc -shared -lc
endif
ifeq ($(UNAME), Darwin)
	CCFLAGS += -I${JAVA_HOME}/include/darwin
	LINKFLAGS += -dynamiclib
endif

all: libtagging.dylib

libtagging.dylib:
	gcc ${CCFLAGS} -o target/tagging.o src/main/c/tagger.cpp
	g++ ${LINKFLAGS} -o target/libtagging.so target/tagging.o

libcriu.dylib:
	gcc ${CCFLAGS} -o target/criu.o src/main/c/criu.cpp -I/usr/include/criu
	g++ ${LINKFLAGS} -o target/libcriu.so target/criu.o -lcriu

clean:
	rm -rf target/tagging.o target/libtagging.dylib target/libtagging.so target/criu.o target/criu.dylib target/libcriu.so
