package com.siteplain.service;

/** Exposes the refresh lock key to tests in other packages. */
public final class DataRefreshLockAccess {

    private DataRefreshLockAccess() {
    }

    public static long key() {
        return DataRefreshLock.LOCK_KEY;
    }
}
