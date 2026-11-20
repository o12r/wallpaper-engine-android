package de.robv.android.xposed.callbacks;

/** Stub of the Xposed API 82 class. */
public abstract class XCallback implements Comparable<XCallback> {
    public final int priority = 50;

    public XCallback() {
    }

    @Override
    public int compareTo(XCallback other) {
        throw new UnsupportedOperationException("stub");
    }

    public abstract static class Param {
        public final Object[] callbacks = null;
    }
}
