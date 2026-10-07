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
package us.bringardner.parley.files;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import us.bringardner.parley.io.ILineReader;
import us.bringardner.parley.io.ILineWriter;
import us.bringardner.parley.io.LFLineReader;
import us.bringardner.parley.io.LFLineWriter;


public class CommandLinePropertyEditor {
	ILineReader in = new LFLineReader(System.in);
	ILineWriter out = new LFLineWriter(System.out);

	public CommandLinePropertyEditor() {
		
	}

	public CommandLinePropertyEditor(ILineReader in, ILineWriter out) {
		this.in = in;
		this.out = out;
	}
	
	public ILineReader getIn() {
		return in;
	}

	public void setIn(ILineReader in) {
		this.in = in;
	}

	public ILineWriter getOut() {
		return out;
	}

	public void setOut(ILineWriter out) {
		this.out = out;
	}

	/**
	 * Asks for each property in turn (they're described as text, or secrets by their names).
	 * @return true if the user accepted the values (props1 then holds them); false if canceled
	 */
	public  boolean editProperties(String name1, Properties props1) throws IOException {
		return editProperties(name1, props1, ConnectionSettings.describe(props1, null, FileSourceFactory::looksLikeSecret));
	}

	/**
	 * Asks for each setting that applies, in turn, until the user enters 'ok' or 'done'
	 * (accepted if the values are valid) or 'cancel' or 'exit'. Secrets are never shown.
	 *
	 * @param name1 a connection name: an empty value falls back to name1.key in props1, then
	 *        in the system properties
	 * @param props1 the values to start from; on acceptance, the values entered
	 * @param settings what to ask for (from {@link FileSourceFactory#getConnectionSettings()})
	 * @return true if accepted
	 */
	public  boolean editProperties(String name1, Properties props1, List<ConnectionSetting> settings) throws IOException {

		Properties values = new Properties();
		values.putAll(props1);
		for(ConnectionSetting s : settings) {
			String val = values.getProperty(s.key());
			if( val == null || val.isEmpty()) {
				val = props1.getProperty(name1+"."+s.key());
				if( val == null || val.isEmpty()) {
					val = System.getProperty(name1+"."+s.key());
				}
			}
			values.setProperty(s.key(), val == null || val.isEmpty() ? s.defaultValue() : val);
		}

		out.writeLine("There are "+settings.size()+" connection settings.");
		out.writeLine("Press enter to keep a value. Enter 'ok' or 'done' when you are done editing, 'cancel' to stop.");

		while(true) {
			for(ConnectionSetting s : settings) {
				if( !ConnectionSettings.isVisible(s, settings, values)) {
					continue;
				}
				String val = values.getProperty(s.key(), "");
				String shown = s.isSecret() ? (val.isEmpty() ? "not set" : "set") : "'"+val+"'";
				String prompt = "Enter "+s.label()
					+(s.kind() == ConnectionSetting.Kind.CHOICE ? " ("+String.join(", ", s.choices())+")" : "")
					+(s.description().isEmpty() ? "" : " - "+s.description())
					+" or enter to keep "+shown;

				while(true) {
					out.writeLine(prompt);
					String line = in.readLine();
					if( line == null ) {
						line = "exit";
					}
					String cmd = line.trim();
					if( "cancel".equals(cmd) || "exit".equals(cmd)) {
						return false;
					} else if( "done".equals(cmd) || "ok".equals(cmd)) {
						List<String> problems = ConnectionSettings.validate(settings, values);
						if( problems.isEmpty()) {
							props1.putAll(ConnectionSettings.forConnect(settings, values));
							return true;
						}
						for(String p : problems) {
							out.writeLine(p);
						}
						continue;
					}
					String value = line.isEmpty() ? val : (s.kind() == ConnectionSetting.Kind.MULTILINE_SECRET ? line : cmd);
					String problem = s.check(value);
					if( problem != null ) {
						out.writeLine(problem);
						continue;
					}
					values.setProperty(s.key(), value);
					break;
				}
			}
		}
	}

	public static void main(String[] args) {
		// Not implemented

	}

}
