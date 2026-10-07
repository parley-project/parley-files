/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
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
/*
 * ConnectioinPropertiesDialog.java
 *
 * 
 */

package us.bringardner.parley.files;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 *
 * @author  Tony Bringardner
 */
public class FactoryPropertiesDialog extends javax.swing.JDialog {

	private static final long serialVersionUID = 1L;
	private volatile boolean cancel;
	private FileSourceFactory factory;
	private final ConnectionSettingsPanel settingsPanel = new ConnectionSettingsPanel();
	private volatile boolean testing;
	private boolean accepted;
	private JButton cancelButton;
	private JButton okButton;
	private JButton testButton;
	private JComboBox<String> comboBox;
	// true while the combo box is set from code, so it doesn't replace the factory
	private boolean settingCombo;

	
	/** Creates new form ConnectioinPropertiesDialog */
	public FactoryPropertiesDialog() {
		super();
		setModal(true);
		initComponents();
	}



	public boolean isCancel() {
		return cancel;
	}



	public FileSourceFactory getFactory() {
		return factory;
	}



	private void initComponents() {

		JPanel southPanel = new JPanel();
		testButton = new JButton("Test Connection");
		okButton = new JButton("OK");
		cancelButton = new JButton("Cancel");

		setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);

		testButton.addActionListener(e->testButtonActionPerformed());
		southPanel.add(testButton);
		okButton.addActionListener(e->okButtonActionPerformed());
		southPanel.add(okButton);
		cancelButton.addActionListener(e->cancelButtonActionPerformed());
		southPanel.add(cancelButton);
		getContentPane().add(southPanel, BorderLayout.SOUTH);

		getContentPane().add(settingsPanel, BorderLayout.CENTER);

		JPanel northPanel = new JPanel();
		getContentPane().add(northPanel, BorderLayout.NORTH);
		northPanel.setLayout(new BorderLayout(0, 0));

		comboBox = new JComboBox<String>();
		comboBox.setModel(new DefaultComboBoxModel<String>(getFactories()));
		comboBox.addActionListener(e->{
			if( !settingCombo ) {
				setFactory(FileSourceFactory.getFileSourceFactory(comboBox.getSelectedItem().toString()));
			}
		});
		northPanel.add(comboBox, BorderLayout.WEST);

		getRootPane().setDefaultButton(okButton);
		pack();
	}

	/**
	 * 
	 * @return list of all remote file systems 
	 */
	private String [] getFactories() {
		String[] avail = FileSourceFactory.getRegisterdFactories();
		List<String> list = new ArrayList<String>();
		String proxy = FileSourceFactory.fileProxyFactory.getTypeId();
		for (String nm : avail) {
			if( !proxy.equals(nm)) {
				list.add(nm);
			}
		}

		if( list.size() == 0) {
			list.add(proxy);
		}

		return list.toArray(new String[list.size()]);
	}

	/** True if the values are usable; otherwise tells the user what's wrong. */
	private boolean checkValues() {
		List<String> problems = new ArrayList<>(settingsPanel.validateValues());
		if( problems.isEmpty()) {
			problems.addAll(factory.validateConnection(settingsPanel.getProperties()));
		}
		if( !problems.isEmpty()) {
			JOptionPane.showMessageDialog(this, String.join("\n", problems), "Check the settings", JOptionPane.WARNING_MESSAGE);
			return false;
		}
		return true;
	}

	private void testButtonActionPerformed() {
		if( !testing && checkValues()) {
			testConnect(settingsPanel.getProperties(),
					()->JOptionPane.showMessageDialog(this, "Connected to "+factory.getTitle(), "", JOptionPane.INFORMATION_MESSAGE),
					()->JOptionPane.showMessageDialog(this, "Could not connect to "+factory.getTitle(),"", JOptionPane.ERROR_MESSAGE));
		}
	}

	/** Connects in the background; ok or failed then runs on the event thread. */
	private synchronized void testConnect(final Properties properties, final Runnable ok, final Runnable failed)  {
		testing = true;
		final Cursor current = getCursor();
		setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		new Thread(()->{
			Runnable result;
			try {
				if( factory.connect(properties)) {
					factory.listRoots();
					result = ok;
				} else {
					factory.disConnect();
					result = failed;
				}
			}  catch (Exception e) {
				result = ()->JOptionPane.showMessageDialog(FactoryPropertiesDialog.this, "Error:"+e, "Could not connect", JOptionPane.ERROR_MESSAGE);
			}
			Runnable r = result;
			SwingUtilities.invokeLater(()->{
				setCursor(current);
				testing = false;
				if( r != null && !isCancel()) {
					r.run();
				}
			});
		}, "Connect "+factory.getTypeId()).start();
	}



	private void cancelButtonActionPerformed() {
		this.cancel = true;
		dispose();
	}

	private void okButtonActionPerformed() {
		if( !testing && checkValues()) {
			testConnect(settingsPanel.getProperties(), ()->{
				accepted = true;
				dispose();
			}, ()->JOptionPane.showMessageDialog(this, "Could not connect to "+factory.getTitle(),"", JOptionPane.ERROR_MESSAGE));
		}
	}


	public void setFactory(FileSourceFactory factory) {
		this.factory = factory;
		settingCombo = true;
		try {
			comboBox.setSelectedItem(factory.getTypeId());
		} finally {
			settingCombo = false;
		}
		settingsPanel.setFactory(factory);
		pack();
	}

	public void showDialog() {
		showDialog(null);
	}

	/** @param factory the factory to edit (with its current settings), or null to start with the first type */
	public void showDialog(FileSourceFactory factory) {
		setLocationRelativeTo(null);
		if( factory == null ) {
			String[] fids = getFactories();
			factory = FileSourceFactory.getFileSourceFactory(fids[0]);
		}
		setFactory(factory);

		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent arg0) {
				if( ! accepted ) {
					//  if the users closes the window without pressing ok or cancel we cancel the operation
					cancel = true;
				}
			}
			
		});
		setVisible(true);
		if(!cancel) {
			this.factory.setConnectionProperties(settingsPanel.getProperties());
		}
	}



}
