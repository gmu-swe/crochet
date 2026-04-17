/**
 * PoC target for 2-slot field handling. Has one field of every scalar flavor
 * the wrapper must cope with, including J (long) and D (double).
 */
public class Scalar {
    public int    iField;
    public long   lField;   // 2-slot
    public double dField;   // 2-slot
    public Object oField;
    public String sField;

    public Scalar(int i, long l, double d, Object o, String s) {
        this.iField = i;
        this.lField = l;
        this.dField = d;
        this.oField = o;
        this.sField = s;
    }

    @Override
    public String toString() {
        return "Scalar{i=" + iField
                + ", l=" + lField
                + ", d=" + dField
                + ", o=" + oField
                + ", s=" + sField + "}";
    }
}
