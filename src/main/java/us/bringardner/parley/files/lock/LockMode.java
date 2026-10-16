package us.bringardner.parley.files.lock;

/** What a lock allows others to hold at the same time. */
public enum LockMode {

	/** No other holder of any kind. */
	EXCLUSIVE,

	/** Any number of other SHARED holders, and no EXCLUSIVE one. */
	SHARED
}
