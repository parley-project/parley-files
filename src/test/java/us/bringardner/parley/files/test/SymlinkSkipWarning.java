package us.bringardner.parley.files.test;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Says, once the tests have run, that the symbolic link tests were skipped because this process
 * can't create links (on Windows: no Developer Mode, not elevated). A skip is not a failure, so a
 * build can end in SUCCESS with those tests never having run; this keeps that from going unseen.
 * <p>
 * Registered with the ServiceLoader (META-INF/services), so JUnit finds it with no configuration.
 * To make the skip fail the build, run with {@code -Dparley.requireSymlinks=true}
 * (see {@code SymbolicLinkSupportTests}).
 */
public class SymlinkSkipWarning implements TestExecutionListener {

	/** The text of the skip message; {@code FileLikeBehaviorTests} puts it in its assumption. */
	public static final String MARKER = "this process cannot create symbolic links";

	private final AtomicInteger skipped = new AtomicInteger();

	@Override
	public void executionFinished(TestIdentifier id, TestExecutionResult result) {
		if( result.getStatus() == TestExecutionResult.Status.ABORTED ) {
			String message = result.getThrowable().map(Throwable::getMessage).orElse("");
			if( message != null && message.contains(MARKER) ) {
				skipped.incrementAndGet();
			}
		}
	}

	@Override
	public void testPlanExecutionFinished(TestPlan plan) {
		int n = skipped.get();
		if( n > 0 ) {
			String bar = "=".repeat(78);
			System.out.println();
			System.out.println(bar);
			System.out.println("WARNING: " + n + " symbolic link test(s) were SKIPPED, not run.");
			System.out.println(MARKER.substring(0, 1).toUpperCase() + MARKER.substring(1) + ".");
			System.out.println("On Windows that needs Developer Mode, or a shell run as Administrator.");
			System.out.println("The build is not wrong, but those tests have not checked anything.");
			System.out.println("See Readme.md, \"Symbolic links on Windows\". To make this fail the");
			System.out.println("build instead, run with -Dparley.requireSymlinks=true");
			System.out.println(bar);
		}
	}
}
