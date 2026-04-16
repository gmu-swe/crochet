# Build + drive the JVMTI SINGLE_STEP overhead spike.
#
# Usage:
#   make                 # build native + java
#   make run             # run all four configs
#   make printcomp       # re-run "always" with -XX:+PrintCompilation
#   make clean

JAVA_HOME ?= /usr/lib/jvm/java-21-openjdk-amd64

CXX       ?= g++
CXXFLAGS  := -std=c++11 -O2 -fPIC -shared \
             -I$(JAVA_HOME)/include \
             -I$(JAVA_HOME)/include/linux
LDFLAGS   :=

AGENT     := libspike.so
JAVAC     := $(JAVA_HOME)/bin/javac
JAVA      := $(JAVA_HOME)/bin/java

.PHONY: all run printcomp clean build-agent build-java

all: build-agent build-java

build-agent: $(AGENT)

$(AGENT): agent.cpp
	$(CXX) $(CXXFLAGS) agent.cpp -o $(AGENT) $(LDFLAGS)

build-java: Bench.class

Bench.class: Bench.java
	$(JAVAC) Bench.java

run: all
	@echo "=== config=base (no agent) ==="
	$(JAVA) -cp . Bench base
	@echo
	@echo "=== config=loaded (agent loaded, SS never enabled) ==="
	$(JAVA) -agentpath:./$(AGENT) -Djava.library.path=. -cp . Bench loaded
	@echo
	@echo "=== config=always (SS enabled whole region) ==="
	$(JAVA) -agentpath:./$(AGENT) -Djava.library.path=. -cp . Bench always
	@echo
	@echo "=== config=region (SS toggled inside hot loop) ==="
	$(JAVA) -agentpath:./$(AGENT) -Djava.library.path=. -cp . Bench region

printcomp: all
	@echo "=== PrintCompilation for config=base -> printcomp-base.log ==="
	$(JAVA) -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation \
		-cp . Bench base > printcomp-base.log 2>&1 || true
	@echo "=== PrintCompilation for config=always -> printcomp-always.log ==="
	$(JAVA) -agentpath:./$(AGENT) -Djava.library.path=. \
		-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation \
		-cp . Bench always > printcomp-always.log 2>&1 || true
	@echo "=== PrintCompilation for config=loaded -> printcomp-loaded.log ==="
	$(JAVA) -agentpath:./$(AGENT) -Djava.library.path=. \
		-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation \
		-cp . Bench loaded > printcomp-loaded.log 2>&1 || true
	@echo
	@echo "Look for 'made not entrant' / 'made zombie' / 'deoptimized' in *.log"

clean:
	rm -f $(AGENT) Bench.class printcomp-*.log results.txt
