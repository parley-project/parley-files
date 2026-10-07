/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 * 
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *   
 *   
 *	@author Tony Bringardner   
 *
 *
 * ~version~V000.00.01-V000.00.00-
 */
package us.bringardner.parley.files;

import javax.swing.ProgressMonitor;
import javax.swing.SwingUtilities;

/** A Swing ProgressMonitor as a {@link FileSourceProgress}. */
public class ProgressMonitorProgress implements FileSourceProgress {

	private final ProgressMonitor monitor;

	public ProgressMonitorProgress(ProgressMonitor monitor) {
		this.monitor = monitor;
	}

	@Override
	public void setMaximum(int max) {
		SwingUtilities.invokeLater(()->monitor.setMaximum(max));
	}

	@Override
	public int getMaximum() {
		return monitor.getMaximum();
	}

	@Override
	public void setProgress(int value) {
		SwingUtilities.invokeLater(()->monitor.setProgress(value));
	}

	@Override
	public boolean isCanceled() {
		return monitor.isCanceled();
	}
}
