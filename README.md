# java-cr

OK here's the instrumentation strategy:
Add an $old and $version field to every object.
Add a $field_old and $field_version for every static field
Add a $helperClass static field to point to a cached anon class 

Add $$get to each class for every field. This one is the naive fast path that has no checks.
Generate on-demand the "slow" version that extends the regular version

For instance fields:
We solve this all by switching the class of every object to the slow version (lazily)

For static fields:
We generate a helper object for each class. We swap the class of that object just like we swap the class of any other.


Currently, the instrumentation:
* Only enabled on things in net/jonbell/crij/test/
* Patches in the array wrappers
* Replaces field accesses with calls to the get/set
* Generates the slow access classes
* See CheckpointRollbackAgent for the code that will generate the stub classes.
* Run existing integration tests: mvn verify
* To see the generated code, you'll find it in the debug/ folder
* For static fields, is currently just doing the fast path. Need to change to use invokedynamic/methodhandles, but will do this later