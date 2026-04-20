JAVA_HOME ?= /usr/lib/jvm/java-21-openjdk-amd64
CFLAGS    := -shared -fPIC -O2 \
             -I$(JAVA_HOME)/include \
             -I$(JAVA_HOME)/include/linux

all: librace-agent.so classes/repro/JoinRepro.class

librace-agent.so: race-agent.c
	gcc $(CFLAGS) $< -o $@

classes/repro/JoinRepro.class: src/repro/JoinRepro.java
	mkdir -p classes
	javac -d classes $<

clean:
	rm -f librace-agent.so
	rm -rf classes
