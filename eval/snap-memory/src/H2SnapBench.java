import java.sql.*;
import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

/**
 * Synthetic H2 snapshot benchmark for A.1 measurement.
 * Checkpoints but does NOT rollback — measures fastAccess volume
 * during a realistic database workload. H2's heavy use of complex
 * object graphs (MVStore BTree, Page nodes, Value objects) exercises
 * the same code paths as the DaCapo h2 benchmark.
 *
 * Using checkpointAll() without rollback lets us accumulate fastAccess
 * counts across many objects without triggering the known StackOverflowError
 * in rollback propagation through ThreadLocal internals.
 */
public class H2SnapBench {
    public static void main(String[] args) throws Exception {
        int txns = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        int cpInterval = args.length > 1 ? Integer.parseInt(args[1]) : 200;

        Class.forName("org.h2.Driver");
        Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:bench;DB_CLOSE_DELAY=-1", "sa", "");

        Statement stmt = conn.createStatement();
        stmt.execute("CREATE TABLE orders(id INT PRIMARY KEY, customer_id INT, amount DOUBLE, status VARCHAR(20))");
        stmt.execute("CREATE TABLE customers(id INT PRIMARY KEY, name VARCHAR(100), balance DOUBLE)");
        stmt.execute("CREATE INDEX idx_customer ON orders(customer_id)");

        // Insert base data (warmup)
        PreparedStatement ins = conn.prepareStatement("INSERT INTO customers VALUES(?,?,?)");
        for (int i = 0; i < 200; i++) {
            ins.setInt(1, i);
            ins.setString(2, "Customer-" + i);
            ins.setDouble(3, 10000.0);
            ins.addBatch();
        }
        ins.executeBatch();

        PreparedStatement insO = conn.prepareStatement("INSERT INTO orders VALUES(?,?,?,?)");
        for (int i = 0; i < 1000; i++) {
            insO.setInt(1, i);
            insO.setInt(2, i % 200);
            insO.setDouble(3, Math.random() * 1000);
            insO.setString(4, "PENDING");
            insO.addBatch();
        }
        insO.executeBatch();

        System.out.println("Warmup complete. Starting measurement...");

        PreparedStatement upd = conn.prepareStatement("UPDATE orders SET status=? WHERE id=?");
        PreparedStatement sel = conn.prepareStatement("SELECT id, balance FROM customers WHERE id=?");
        PreparedStatement updBal = conn.prepareStatement("UPDATE customers SET balance=balance+? WHERE id=?");

        int checkpoints = 0;
        long t0 = System.nanoTime();

        for (int i = 0; i < txns; i++) {
            int oid = i % 1000;
            int cid = oid % 200;

            upd.setString(1, i % 3 == 0 ? "SHIPPED" : "PROCESSING");
            upd.setInt(2, oid);
            upd.executeUpdate();

            sel.setInt(1, cid);
            ResultSet rs = sel.executeQuery();
            rs.close();

            updBal.setDouble(1, (i % 5 == 0) ? -100.0 : 50.0);
            updBal.setInt(2, cid);
            updBal.executeUpdate();

            if (i % cpInterval == 0) {
                CheckpointRollbackAgent.checkpointAll();
                checkpoints++;
            }
        }

        long elapsed = System.nanoTime() - t0;
        System.out.printf("H2SnapBench: %d txns, %d checkpoints, %.2f ms%n",
                txns, checkpoints, elapsed / 1e6);

        conn.close();
    }
}
