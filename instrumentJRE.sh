#!/bin/sh
if [ -z "$INST_HOME" ]; then
	INST_HOME=$JAVA_HOME;
fi
if [ -z "$JAVA_HOME" ]; then
	echo "Error: Please set \$JAVA_HOME";
else
	echo "Ensuring instrumented JREs exist for tests... to refresh, do mvn clean\n";
	if [ ! -d "target/jre-inst" ]; then
		echo "Creating instrumented JRE\n";
    	$JAVA_HOME/bin/java -Xmx6g -jar target/CRIJ-0.0.1-SNAPSHOT.jar $INST_HOME target/jre-inst;
		chmod +x target/jre-inst/bin/*;
		chmod +x target/jre-inst/lib/*;
		chmod +x target/jre-inst/jre/bin/*;
		chmod +x target/jre-inst/jre/lib/*;
	else
		echo "Not regenerating JRE\n";
	fi
fi
