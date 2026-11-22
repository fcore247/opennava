package site.navatv.keys;

/** Supplies only the firmware's physical Home signal to the same-signer Rust launcher. */
public final class RustHomeSignalService extends KeySignalService {
    @Override protected boolean homeOnly() { return true; }
}
