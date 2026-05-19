package net.jonbell.crochet.tests;

import net.jonbell.crochet.annotation.CrochetEager;

/**
 * Fixture for the Diff API tests (A.3). Uses eager-mode so the snap is populated
 * inline on {@code $$crochetCheckpoint} without needing a Fast-proxy round-trip.
 * All field types covered by the validation matrix are present.
 */
@CrochetEager
public class DiffBean {

    // --- primitive fields (all 8 types) ---
    public int    fInt;
    public long   fLong;
    public double fDouble;
    public float  fFloat;
    public boolean fBool;
    public byte   fByte;
    public char   fChar;
    public short  fShort;

    // --- reference field ---
    public Object fRef;

    // --- primitive arrays ---
    public int[]    fIntArr;
    public long[]   fLongArr;
    public double[] fDoubleArr;
    public float[]  fFloatArr;
    public boolean[] fBoolArr;
    public byte[]   fByteArr;
    public char[]   fCharArr;
    public short[]  fShortArr;

    // --- reference array ---
    public Object[] fRefArr;

    // --- self-reference (cycle test) ---
    public DiffBean self;

    public DiffBean() {}
}
