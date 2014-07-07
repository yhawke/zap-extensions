/*
 * Zed Attack Proxy (ZAP) and its related class files.
*
* ZAP is an HTTP/HTTPS proxy for assessing web application security.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.zaproxy.zap.extension.ascanrulesAlpha;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.core.scanner.AbstractHostPlugin;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.core.scanner.Category;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.model.Vulnerabilities;
import org.zaproxy.zap.model.Vulnerability;

import com.strobel.decompiler.Decompiler;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;

/**
* a scanner that looks for Java classes disclosed via the WEB-INF folder
* and that decompiles them to give the Java source code    
* 
* @author 70pointer
*
*/
public class SourceCodeDisclosureWEBINF extends AbstractHostPlugin {
	
	//TODO: for imported classes that we do not find in the classes folder, map to jar file names, which we might find in WEB-INF/lib/ ?
	//TODO: pull referenced properties files from WEB-INF? 
	 

	/**
	 * the set of files that commonly occur in the WEB-INF folder
	 */
	private static final List <String> WEBINF_FILES = new LinkedList<String>(Arrays.asList(new String [] 
			{
			"web.xml"
			}
			));
	/**
	 * match on Java class names (including the package info)
	 * we're "flexible" on the package names and class names containing uppercase versus lowercase, by necessity.
	 */
	private static final Pattern JAVA_CLASSNAME_PATTERN = Pattern.compile("[0-9a-zA-Z_.]+\\.[a-zA-Z0-9_]+");
	
	/**
	 * match on imports in the decompiled Java source, to find the names of more classes to pull
	 */
	private static final Pattern JAVA_IMPORT_CLASSNAME_PATTERN = Pattern.compile("^import\\s+([0-9a-zA-Z_.]+\\.[a-zA-Z0-9_]+);", Pattern.MULTILINE);
	
	/**
	 * details of the vulnerability which we are attempting to find 
	 * 34 = Predictable Resource Location
	 */
	private static final Vulnerability vuln = Vulnerabilities.getVulnerability("wasc_34");

	/**
	 * the logger object
	 */
	private static final Logger log = Logger.getLogger(SourceCodeDisclosureWEBINF.class);


	/**
	 * returns the plugin id
	 */
	@Override
	public int getId() {
		return 10045;
	}

	/**
	 * returns the name of the plugin
	 */
	@Override
	public String getName() {
		return Constant.messages.getString("ascanalpha.sourcecodedisclosurewebinf.name");
	}

	@Override
	public String[] getDependency() {
		return null;
	}

	@Override
	public String getDescription() {
		if (vuln != null) {
			return vuln.getDescription();
		}
		return "Failed to load vulnerability description from file";
	}

	@Override
	public int getCategory() {
		return Category.INFO_GATHER;
	}

	@Override
	public String getSolution() {
		if (vuln != null) {
			return vuln.getSolution();
		}
		return "Failed to load vulnerability solution from file";
	}

	@Override
	public String getReference() {
		if (vuln != null) {
			StringBuilder sb = new StringBuilder();
			for (String ref : vuln.getReferences()) {
				if (sb.length() > 0) {
					sb.append('\n');
				}
				sb.append(ref);
			}
			return sb.toString();
		}
		return "Failed to load vulnerability reference from file";
	}

	@Override
	public void init() {		
	}


	@Override
	public void scan() {
		try {
			URI originalURI = getBaseMsg().getRequestHeader().getURI();
			List <String> javaClassesFound = new LinkedList<String>();
			List <String> javaClassesHandled = new LinkedList<String>();
			
			//Pass 1: thru each of the WEB-INF files, looking for class names 
			for ( String filename : WEBINF_FILES) {
				
				HttpMessage webinffilemsg = new HttpMessage(new URI(originalURI.getScheme()+ "://" +originalURI.getAuthority()+ "/WEB-INF/"+ filename, true));
				sendAndReceive(webinffilemsg, false); //do not follow redirects
				String body = new String (webinffilemsg.getResponseBody().getBytes());
				Matcher matcher = JAVA_CLASSNAME_PATTERN.matcher(body);
				while (matcher.find()) {
					//we have a possible class *name*.  
					//Next: See if the class file lives in the expected location in the WEB-INF folder
					String classname = matcher.group();
					if (! javaClassesFound.contains(classname)) javaClassesFound.add(classname);
				}
			}
			
			//for each class name found, try download the actual class file..
			//for ( String classname: javaClassesFound) {
			while(javaClassesFound.size() > 0) {
				String classname = javaClassesFound.get(0); 
			
				URI classURI = getClassURI (originalURI, classname);
				if ( log.isDebugEnabled() ) {
					log.debug("Looking for a potential Java class: "+ classname + " at "+classURI.getURI());					
				}
				
				HttpMessage classfilemsg = new HttpMessage(classURI);
				sendAndReceive(classfilemsg, false); //do not follow redirects
				if (classfilemsg.getResponseHeader().getStatusCode() == HttpStatus.SC_OK ) {
					if ( log.isDebugEnabled() ) {
						log.debug(classname + " is accessible :)");
					}
					//to decompile the class file, we need to write it to disk..
					//under the current version of the library, at least
					File classFile = null;
					try {
						classFile = File.createTempFile("zap", ".class");
						classFile.deleteOnExit();
						OutputStream fos = new FileOutputStream (classFile);
						fos.write(classfilemsg.getResponseBody().getBytes());
						fos.close();
						
						//now decompile it
						DecompilerSettings decompilerSettings = new DecompilerSettings();
				        
						//set some options so that we can better parse the output, to get the names of more classes..
						decompilerSettings.setForceExplicitImports(true);
						decompilerSettings.setForceExplicitTypeArguments(true);
				        
						PlainTextOutput decompiledText = new PlainTextOutput(); 
						Decompiler.decompile (classFile.getAbsolutePath(), decompiledText, decompilerSettings);
						String javaSourceCode = decompiledText.toString();
						
						if ( log.isDebugEnabled() ) {
							log.debug("Source Code Disclosure alert for: "+ classname);
						}
						
						//bingo.
						bingo(	Alert.RISK_HIGH, 
							Alert.WARNING,
							Constant.messages.getString("ascanalpha.sourcecodedisclosurewebinf.name"),
							Constant.messages.getString("ascanalpha.sourcecodedisclosurewebinf.desc"), 
							null, // originalMessage.getRequestHeader().getURI().getURI(),
							null, // parameter being attacked: none.
							"",  // attack
							javaSourceCode, //extrainfo
							Constant.messages.getString("ascanalpha.sourcecodedisclosurewebinf.soln"),
							"",		//evidence, highlighted in the message
							classfilemsg	//raise the alert on the classfile, rather than on the web.xml (or other file where the class reference was found).
							);	
						
						//and add the referenced classes to the list of classes to look for!
						//so that we catch as much source code as possible.
						Matcher importMatcher = JAVA_IMPORT_CLASSNAME_PATTERN.matcher(javaSourceCode);
						while (importMatcher.find()) {
							//we have another possible class name.  
							//Next: See if the class file lives in the expected location in the WEB-INF folder
							String importClassname = importMatcher.group(1);
							if (log.isDebugEnabled()) log.debug("Imported class: "+importClassname);
							
							if ( (! javaClassesFound.contains(importClassname)) &&
								 (! javaClassesHandled.contains(importClassname))) {
								if (log.isDebugEnabled()) log.debug("Adding imported class "+importClassname + " to the list of classes to handle");
								javaClassesFound.add(importClassname);
							}
						}
						
						//do not return at this point.. there may be multiple classes referenced. We want to see as many of them as possible.							
						}						
					finally {	
						//delete the temp file.
						//this will be deleted when the VM is shut down anyway, but just in case!				
						if (classFile!=null) classFile.delete();							
					}						
				}
			//remove the class from the set to handle, and add it to the list of classes handled
			javaClassesFound.remove(classname);
			javaClassesHandled.add(classname);
			}			
		} catch (Exception e) {
			log.error("Error scanning a Host for Source Code Disclosure via the WEB-INF folder: " + e.getMessage(), e);
		}
		
	}
	
	/**
	 * gets a candidate URI for a given class path. 
	 * @param classname
	 * @return
	 * @throws URIException 
	 */
	private URI getClassURI(URI hostURI, String classname) throws URIException {
		return new URI (hostURI.getScheme() + "://"+ hostURI.getAuthority() + "/WEB-INF/classes/"+classname.replaceAll("\\.", "/")+".class", false);
	}

	@Override
	public int getRisk() {
		return Alert.RISK_HIGH; 
	}

	@Override
	public int getCweId() {
		return 541;  //Information Exposure Through Include Source Code
	}

	@Override
	public int getWascId() {
		return 34;  //Predictable Resource Location
	}

}