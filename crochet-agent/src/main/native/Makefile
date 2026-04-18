# Build the optional crochet-jvmti native agent.
#
# Usage:
#   make                    # build libcrochet-jvmti.so in this directory
#   JAVA_HOME=... make      # use a specific JDK's headers
#   make clean
#
# The output .so is a stand-alone JVMTI agent. Load it with
#   -agentpath:$(pwd)/libcrochet-jvmti.so
# alongside the regular -javaagent:crochet-agent-*.jar.
#
# Without it loaded, StackRoots.engaged stays false and stack-frame root
# collection is a no-op — the agent runs in heap-roots-only mode (the
# default behaviour pre-JVMTI port).

JAVA_HOME ?= /usr/lib/jvm/java-21-openjdk-amd64

CXX       ?= g++
CXXFLAGS  := -std=c++14 -O2 -fPIC -shared -Wall \
             -I$(JAVA_HOME)/include \
             -I$(JAVA_HOME)/include/linux

OUT       := libcrochet-jvmti.so
SRC       := crochet_jvmti.cpp

.PHONY: all clean
all: $(OUT)

$(OUT): $(SRC)
	$(CXX) $(CXXFLAGS) $(SRC) -o $(OUT)

clean:
	rm -f $(OUT)
