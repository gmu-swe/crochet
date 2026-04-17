import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

public class Main {
    public static void main(String[] args) {
        Node a = new Node("A");
        Node b = new Node("B");
        a.partner = b;
        b.partner = a;

        System.out.println("before: a.partner.name=" + a.partner.name
                + " b.partner.name=" + b.partner.name);

        int v = CheckpointRollbackAgent.checkpoint(a);
        System.out.println("checkpoint version: " + v);

        a.name = "a-mutated";
        b.name = "b-mutated";
        System.out.println("after mutation: a=" + a.name + " b=" + b.name);

        CheckpointRollbackAgent.rollback(a, v);
        System.out.println("after rollback: a=" + a.name + " b=" + b.name);

        if ("A".equals(a.name) && "B".equals(b.name)
                && a.partner == b && b.partner == a) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL: a=" + a.name + " b=" + b.name);
            System.exit(1);
        }
    }
}
