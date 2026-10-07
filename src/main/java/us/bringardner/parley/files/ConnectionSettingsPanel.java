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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * A Swing form for any factory's {@link ConnectionSetting}s: a field of the right kind for
 * each, the settings that don't apply hidden, and the advanced ones behind a check box.
 */
public class ConnectionSettingsPanel extends JPanel implements IConnectionPropertiesEditor {

	private static final long serialVersionUID = 1L;

	/** One setting's label and field. */
	private static class Row {
		final ConnectionSetting setting;
		final JLabel label;
		final JComponent field;
		final JComponent editor;

		Row(ConnectionSetting setting, JLabel label, JComponent field, JComponent editor) {
			this.setting = setting;
			this.label = label;
			this.field = field;
			this.editor = editor;
		}

		String value() {
			if( editor instanceof JPasswordField ) {
				return new String(((JPasswordField) editor).getPassword());
			} else if( editor instanceof JTextField ) {
				return ((JTextField) editor).getText().trim();
			} else if( editor instanceof JTextArea ) {
				return ((JTextArea) editor).getText();
			} else if( editor instanceof JCheckBox ) {
				return ""+((JCheckBox) editor).isSelected();
			} else if( editor instanceof JComboBox<?> ) {
				Object item = ((JComboBox<?>) editor).getSelectedItem();
				return item == null ? "" : item.toString();
			}
			return "";
		}
	}

	private List<ConnectionSetting> settings = new ArrayList<>();
	private final Map<String,Row> rows = new LinkedHashMap<>();
	// values of settings without a field (hidden ones, and properties nobody described)
	private final Properties carried = new Properties();
	private final JPanel form = new JPanel(new GridBagLayout());
	private final JCheckBox showAdvanced = new JCheckBox("Show advanced settings");
	private final JLabel none = new JLabel("There are no connection settings for this file system.");

	public ConnectionSettingsPanel() {
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));
		add(form, BorderLayout.CENTER);
		showAdvanced.addActionListener(e->updateVisibility());
		add(showAdvanced, BorderLayout.SOUTH);
	}

	/** Shows a form for settings, filled from values (missing ones get their defaults). */
	public void setSettings(List<ConnectionSetting> settings, Properties values) {
		this.settings = new ArrayList<>(settings);
		rows.clear();
		carried.clear();
		form.removeAll();
		Properties initial = ConnectionSettings.initialValues(settings, values);
		if( values != null ) {
			for(String key : values.stringPropertyNames()) {
				if( ConnectionSettings.find(settings, key) == null ) {
					carried.setProperty(key, values.getProperty(key));
				}
			}
		}
		int y = 0;
		for(ConnectionSetting s : settings) {
			String value = initial.getProperty(s.key(), "");
			if( s.kind() == ConnectionSetting.Kind.HIDDEN ) {
				carried.setProperty(s.key(), value);
				continue;
			}
			Row row = createRow(s, value);
			rows.put(s.key(), row);
			GridBagConstraints c = new GridBagConstraints();
			c.gridy = y++;
			c.insets = new Insets(2, 2, 2, 6);
			c.anchor = GridBagConstraints.LINE_END;
			form.add(row.label, c);
			c = new GridBagConstraints();
			c.gridx = 1;
			c.gridy = y-1;
			c.insets = new Insets(2, 2, 2, 2);
			c.fill = GridBagConstraints.HORIZONTAL;
			c.weightx = 1;
			form.add(row.field, c);
		}
		if( rows.isEmpty()) {
			form.add(none);
		}
		boolean anyAdvanced = settings.stream().anyMatch(s->s.advanced() && s.kind() != ConnectionSetting.Kind.HIDDEN);
		showAdvanced.setVisible(anyAdvanced);
		updateVisibility();
	}

	private Row createRow(ConnectionSetting s, String value) {
		JLabel label = new JLabel(s.label()+(s.required() ? " *" : "")+":");
		JComponent editor;
		JComponent field;
		switch (s.kind()) {
		case SECRET: {
			JPasswordField pw = new JPasswordField(value, 20);
			editor = field = pw;
			break;
		}
		case MULTILINE_SECRET: {
			JTextArea area = new JTextArea(value, 6, 40);
			editor = area;
			field = new JScrollPane(area);
			break;
		}
		case BOOLEAN: {
			JCheckBox box = new JCheckBox();
			box.setSelected(Boolean.parseBoolean(value));
			box.addActionListener(e->updateVisibility());
			editor = field = box;
			break;
		}
		case CHOICE: {
			JComboBox<String> box = new JComboBox<>(s.choices().toArray(new String[0]));
			if( !value.isEmpty() && !s.choices().contains(value)) {
				box.addItem(value);
			}
			box.setSelectedItem(value);
			box.addActionListener(e->updateVisibility());
			editor = field = box;
			break;
		}
		case LOCAL_FILE: {
			JTextField text = new JTextField(value, 20);
			JButton browse = new JButton("Browse...");
			browse.addActionListener(e->browse(text));
			JPanel p = new JPanel(new BorderLayout(4, 0));
			p.add(text, BorderLayout.CENTER);
			p.add(browse, BorderLayout.EAST);
			editor = text;
			field = p;
			break;
		}
		default: {
			JTextField text = new JTextField(value, s.kind() == ConnectionSetting.Kind.INTEGER ? 8 : 20);
			editor = field = text;
		}
		}
		if( editor instanceof JTextField ) {
			// settings can depend on a text value too
			((JTextField) editor).getDocument().addDocumentListener(new DocumentListener() {
				@Override public void insertUpdate(DocumentEvent e) { updateVisibility(); }
				@Override public void removeUpdate(DocumentEvent e) { updateVisibility(); }
				@Override public void changedUpdate(DocumentEvent e) { }
			});
		}
		if( !s.description().isEmpty()) {
			label.setToolTipText(s.description());
			editor.setToolTipText(s.description());
		}
		return new Row(s, label, field, editor);
	}

	private void browse(JTextField text) {
		JFileChooser fc = new JFileChooser();
		if( !text.getText().isBlank()) {
			fc.setSelectedFile(new File(text.getText().trim()));
		}
		fc.setFileHidingEnabled(false);
		if( fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION ) {
			text.setText(fc.getSelectedFile().getAbsolutePath());
		}
	}

	private Properties currentValues() {
		Properties ret = new Properties();
		ret.putAll(carried);
		for(Row r : rows.values()) {
			ret.setProperty(r.setting.key(), r.value());
		}
		return ret;
	}

	private void updateVisibility() {
		Properties values = currentValues();
		boolean changed = false;
		for(Row r : rows.values()) {
			boolean visible = ConnectionSettings.isVisible(r.setting, settings, values)
					&& (!r.setting.advanced() || showAdvanced.isSelected());
			if( r.field.isVisible() != visible ) {
				r.label.setVisible(visible);
				r.field.setVisible(visible);
				changed = true;
			}
		}
		if( changed ) {
			revalidate();
			Window w = SwingUtilities.getWindowAncestor(this);
			if( w != null && w.isShowing()) {
				SwingUtilities.invokeLater(w::pack);
			}
		}
	}

	/** What's wrong with the values entered; empty if nothing is. */
	public List<String> validateValues() {
		return ConnectionSettings.validate(settings, currentValues());
	}

	/** The properties to connect with (settings that don't apply are emptied). */
	@Override
	public Properties getProperties() {
		return ConnectionSettings.forConnect(settings, currentValues());
	}

	/** Fills the form from properties, describing them as text (or secrets) if they haven't been described. */
	@Override
	public void setProperties(Properties properties) {
		if( settings.isEmpty()) {
			setSettings(ConnectionSettings.describe(properties, null, FileSourceFactory::looksLikeSecret), properties);
		} else {
			setSettings(settings, properties);
		}
	}

	/** Fills the form with factory's settings and current values. */
	public void setFactory(FileSourceFactory factory) {
		setSettings(factory.getConnectionSettings(), factory.getConnectProperties());
	}

	@Override
	public Dimension getPreferredSize() {
		Dimension d = super.getPreferredSize();
		return new Dimension(Math.max(d.width, 420), d.height);
	}

	/** The field for key, for tests; null if there's none. */
	Component fieldFor(String key) {
		Row r = rows.get(key);
		return r == null ? null : r.editor;
	}
}
