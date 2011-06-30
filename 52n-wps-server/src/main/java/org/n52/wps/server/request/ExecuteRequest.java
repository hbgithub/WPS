/***************************************************************
 This implementation provides a framework to publish processes to the
web through the  OGC Web Processing Service interface. The framework 
is extensible in terms of processes and data handlers. It is compliant 
to the WPS version 0.4.0 (OGC 05-007r4). 

 Copyright (C) 2006 by con terra GmbH

 Authors: 
	Theodor Foerster, ITC, Enschede, the Netherlands
	Carsten Priess, Institute for geoinformatics, University of
Muenster, Germany


 Contact: Albert Remke, con terra GmbH, Martin-Luther-King-Weg 24,
 48155 Muenster, Germany, 52n@conterra.de

 This program is free software; you can redistribute it and/or
 modify it under the terms of the GNU General Public License
 version 2 as published by the Free Software Foundation.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program (see gnu-gpl v2.txt); if not, write to
 the Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 Boston, MA  02111-1307, USA or visit the web page of the Free
 Software Foundation, http://www.fsf.org.

 Created on: 13.06.2006
 ***************************************************************/
package org.n52.wps.server.request;

import java.net.URLDecoder;
import java.util.Map;
import java.util.concurrent.CancellationException;

import net.opengis.wps.x100.DataInputsType;
import net.opengis.wps.x100.DocumentOutputDefinitionType;
import net.opengis.wps.x100.ExecuteDocument;
import net.opengis.wps.x100.ExecuteDocument.Execute;
import net.opengis.wps.x100.InputDescriptionType;
import net.opengis.wps.x100.InputReferenceType;
import net.opengis.wps.x100.InputType;
import net.opengis.wps.x100.LiteralDataType;
import net.opengis.wps.x100.OutputDefinitionType;
import net.opengis.wps.x100.OutputDescriptionType;
import net.opengis.wps.x100.ProcessDescriptionType;
import net.opengis.wps.x100.ResponseDocumentType;
import net.opengis.wps.x100.ResponseFormType;
import net.opengis.wps.x100.StatusType;

import org.apache.commons.collections.map.CaseInsensitiveMap;
import org.apache.log4j.Logger;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlOptions;
import org.n52.wps.commons.context.ExecutionContext;
import org.n52.wps.commons.context.ExecutionContextFactory;
import org.n52.wps.io.data.IData;
import org.n52.wps.server.AbstractTransactionalAlgorithm;
import org.n52.wps.server.ExceptionReport;
import org.n52.wps.server.IAlgorithm;
import org.n52.wps.server.database.DatabaseFactory;
import org.n52.wps.server.observerpattern.IObserver;
import org.n52.wps.server.observerpattern.ISubject;
import org.n52.wps.server.repository.RepositoryManager;
import org.n52.wps.server.response.ExecuteResponse;
import org.n52.wps.server.response.ExecuteResponseBuilder;
import org.n52.wps.server.response.Response;
import org.n52.wps.util.XMLBeansHelper;
import org.w3c.dom.Document;

/**
 * Handles an ExecuteRequest
 */
public class ExecuteRequest extends Request implements IObserver {

	private static Logger LOGGER = Logger.getLogger(ExecuteRequest.class);
	private ExecuteDocument execDom;
	private Map<String, IData> returnResults;
	private ExecuteResponseBuilder execRespType;
	private IAlgorithm algorithm;


	/**
	 * Creates an ExecuteRequest based on a Document (HTTP_POST)
	 * 
	 * @param doc
	 *            The clients submission
	 * @throws ExceptionReport
	 */
	public ExecuteRequest(Document doc) throws ExceptionReport {
		super(doc);
		/**
		 * Remark Christophe Noel (Spacebel)
		 * - context must be set here because a process instance identifier 
		 * must be returned even if task is not started (see WPS 2.0 CR)...
		 * - There is a kind of doublon (context  Request.id)
		 * I decided to ignore the Context (I don't know where it is relevant but
		 * anyway Request.getUniqueId is used for the statusLocation in the current location
		 * In order to keep the Context.id equilavent the Context id is initialized
		 * later with getUniqueId
		 * 	 */
		getUniqueId();
		try {
			XmlOptions option = new XmlOptions();
			option.setLoadTrimTextBuffer();
			this.execDom = ExecuteDocument.Factory.parse(doc, option);
			if (this.execDom == null) {
				LOGGER.fatal("ExecuteDocument is null");
				throw new ExceptionReport("Error while parsing post data",
						ExceptionReport.MISSING_PARAMETER_VALUE);
			}
		} catch (XmlException e) {
			throw new ExceptionReport("Error while parsing post data",
					ExceptionReport.MISSING_PARAMETER_VALUE, e);
		}

		// validate the client input
		validate();

		// create an initial response
		execRespType = new ExecuteResponseBuilder(this);
	}

	/*
	 * Creates an ExecuteRequest based on a Map (HTTP_GET). NOTE: Parameters are
	 * treated as non case sensitive. @param ciMap The client input @throws
	 * ExceptionReport
	 */
	public ExecuteRequest(CaseInsensitiveMap ciMap) throws ExceptionReport {
		super(ciMap);
		initForGET(ciMap);
		// validate the client input
		validate();

		// create an initial response
		execRespType = new ExecuteResponseBuilder(this);

	}

	public IAlgorithm getAlgorithm() {
		return algorithm;
	}

	public void setAlgorithm(IAlgorithm algorithm) {
		this.algorithm = algorithm;
	}

	/**
	 * @param ciMap
	 */
	private void initForGET(CaseInsensitiveMap ciMap) throws ExceptionReport {
		String version = getMapValue("version", ciMap, true);
		if (!version.equals(Request.SUPPORTED_VERSION)) {
			throw new ExceptionReport("request version is not supported: "
					+ version, ExceptionReport.VERSION_NEGOTIATION_FAILED);
		}
		this.execDom = ExecuteDocument.Factory.newInstance();
		Execute execute = execDom.addNewExecute();
		String processID = getMapValue("Identifier", true);
		if (!RepositoryManager.getInstance().containsAlgorithm(processID)) {
			throw new ExceptionReport("Process does not exist",
					ExceptionReport.INVALID_PARAMETER_VALUE);
		}
		execute.addNewIdentifier().setStringValue(processID);
		DataInputsType dataInputs = execute.addNewDataInputs();
		String dataInputString = getMapValue("DataInputs", true);
		dataInputString = dataInputString.replace("&amp;","&");
		String[] inputs = dataInputString.split(";");
		
		// Handle data inputs
		for (String inputString : inputs) {
			int position = inputString.indexOf("=");
			if (position == -1) {
				throw new ExceptionReport("No \"=\" supplied for attribute: "
						+ inputString, ExceptionReport.MISSING_PARAMETER_VALUE);
			}
			String key = inputString.substring(0, position);
			String value = null;
			if (key.length() + 1 < inputString.length()) {
				// BS int valueDelimiter = inputString.indexOf("@");
				int valueDelimiter = inputString.indexOf("=@");
				if (valueDelimiter != -1 && position + 1 < valueDelimiter) {
					value = inputString.substring(position + 1, valueDelimiter);
				} else {
					value = inputString.substring(position + 1);
				}
			}
			ProcessDescriptionType description = RepositoryManager.getInstance().getProcessDescription(processID);
			InputDescriptionType inputDesc = XMLBeansHelper.findInputByID(key,
					description.getDataInputs());
			if (inputDesc == null) {
				throw new ExceptionReport("Data Identifier not supported: "
						+ key, ExceptionReport.MISSING_PARAMETER_VALUE);
			}
			InputType input = dataInputs.addNewInput();
			input.addNewIdentifier().setStringValue(key);
			// prepare attributes
			String encodingAttribute = null;
			String mimeTypeAttribute = null;
			String schemaAttribute = null;
			String hrefAttribute = null;
			String[] inputItemstemp = inputString.split("=@");
			String[] inputItems = null;
			if (inputItemstemp.length == 2) {
				inputItems = inputItemstemp[1].split("@");
			} else {
				inputItems = inputString.split("@");
			}
			if (inputItemstemp.length > 1) {
				for (int i = 0; i < inputItems.length; i++) {
					int attributePos = inputItems[i].indexOf("=");
					if (attributePos == -1
							|| attributePos + 1 >= inputItems[i].length()) {
						continue;
					}
					String attributeName = inputItems[i].substring(0,
							attributePos);
					String attributeValue = inputItems[i]
							.substring(attributePos + 1);
					attributeValue = URLDecoder.decode(attributeValue);
					if (attributeName.equalsIgnoreCase("encoding")) {
						encodingAttribute = attributeValue;
					} else if (attributeName.equalsIgnoreCase("mimeType")) {
						mimeTypeAttribute = attributeValue;
					} else if (attributeName.equalsIgnoreCase("schema")) {
						schemaAttribute = attributeValue;
					} else if (attributeName.equalsIgnoreCase("href") | attributeName.equalsIgnoreCase("xlink:href")) {
						hrefAttribute = attributeValue;
					} else {
						throw new ExceptionReport(
								"Attribute is not supported: " + attributeName,
								ExceptionReport.INVALID_PARAMETER_VALUE);
					}

				}
			}
				if (inputDesc.isSetComplexData()) {
					// TODO: check for different attributes
					// handling ComplexReference
					if (!(hrefAttribute == null) && !hrefAttribute.equals("")) {
						InputReferenceType reference = input.addNewReference();
						reference.setHref(hrefAttribute);
						if (schemaAttribute != null) {
							reference.setSchema(schemaAttribute);
						} else {
							reference.setSchema(inputDesc.getComplexData()
									.getDefault().getFormat().getSchema());
						}
						if (mimeTypeAttribute != null) {
							reference.setMimeType(mimeTypeAttribute);
						}
						if (encodingAttribute != null) {
							reference.setEncoding(encodingAttribute);
						}

					}
					// Handling ComplexData
					else {
						// TODO
					}
				
			} else if (inputDesc.isSetLiteralData()) {
				LiteralDataType data = input.addNewData().addNewLiteralData();
				if (value == null) {
					throw new ExceptionReport("No value provided for literal: "
							+ inputDesc.getIdentifier().getStringValue(),
							ExceptionReport.MISSING_PARAMETER_VALUE);
				}
				data.setStringValue(value);
			}

		}
		// retrieve status
		boolean status = false;
		String statusString = getMapValue("status", false);
		if (statusString != null) {
			status = Boolean.parseBoolean(statusString);
		}
		boolean store = false;
		String storeString = getMapValue("storeExecuteResponse", false);
		if (storeString != null) {
			store = Boolean.parseBoolean(storeString);
		}
		// Handle ResponseDocument option
		String responseDocument = getMapValue("ResponseDocument", false);
		if (responseDocument != null) {
			String[] outputs = responseDocument.split(";");
			ResponseDocumentType responseDoc = execute.addNewResponseForm()
					.addNewResponseDocument();
			responseDoc.setStatus(status);
			responseDoc.setStoreExecuteResponse(store);
			for (String outputID : outputs) {
				String[] outputDataparameters = outputID.split("@");
				String outputDataInput = "";
				if (outputDataparameters.length > 0) {
					outputDataInput = outputDataparameters[0];
				} else {
					outputDataInput = outputID;
				}
				ProcessDescriptionType description = RepositoryManager.getInstance().getProcessDescription(processID);
				OutputDescriptionType outputDesc = XMLBeansHelper
						.findOutputByID(outputDataInput, description.getProcessOutputs()
								.getOutputArray());
				if (outputDesc == null) {
					throw new ExceptionReport(
							"Data output Identifier not supported: "
									+ outputDataInput,
							ExceptionReport.MISSING_PARAMETER_VALUE);
				}
				DocumentOutputDefinitionType output = responseDoc
						.addNewOutput();
				output.addNewIdentifier().setStringValue(outputID);

				for (int i = 0; i < outputDataparameters.length; i++) {
					int attributePos = outputDataparameters[i].indexOf("=");
					if (attributePos == -1
							|| attributePos + 1 >= outputDataparameters[i]
									.length()) {
						continue;
					}
					String attributeName = outputDataparameters[i].substring(0,
							attributePos);
					String attributeValue = outputDataparameters[i]
							.substring(attributePos + 1);
					attributeValue = URLDecoder.decode(attributeValue);
					if (attributeName.equalsIgnoreCase("mimeType")) {
						output.setMimeType(attributeValue);
					} else if (attributeName.equalsIgnoreCase("schema")) {
						output.setSchema(attributeValue);
					} else if (attributeName.equalsIgnoreCase("encoding")) {
						output.setEncoding(attributeValue);

					}
				}
			}
		}
		String rawData = getMapValue("RawDataOutput", false);
		if (rawData != null) {
			String[] rawDataparameters = rawData.split("@");
			String rawDataInput = "";
			if (rawDataparameters.length > 0) {
				rawDataInput = rawDataparameters[0];
			} else {
				rawDataInput = rawData;
			}
			ProcessDescriptionType description = RepositoryManager.getInstance().getProcessDescription(processID);
			OutputDescriptionType outputDesc = XMLBeansHelper.findOutputByID(
					rawDataInput, 
							description.getProcessOutputs().getOutputArray());
			if (outputDesc == null) {
				throw new ExceptionReport(
						"Data output Identifier not supported: " + rawData,
						ExceptionReport.MISSING_PARAMETER_VALUE);
			}
			ResponseFormType responseForm = execute.addNewResponseForm();
			OutputDefinitionType output = responseForm.addNewRawDataOutput();
			output.addNewIdentifier().setStringValue(
					outputDesc.getIdentifier().getStringValue());

			if (rawDataparameters.length > 0) {
				for (int i = 0; i < rawDataparameters.length; i++) {
					int attributePos = rawDataparameters[i].indexOf("=");
					if (attributePos == -1
							|| attributePos + 1 >= rawDataparameters[i]
									.length()) {
						continue;
					}
					String attributeName = rawDataparameters[i].substring(0,
							attributePos);
					String attributeValue = rawDataparameters[i]
							.substring(attributePos + 1);
					attributeValue = URLDecoder.decode(attributeValue);
					if (attributeName.equalsIgnoreCase("mimeType")) {
						output.setMimeType(attributeValue);
					} else if (attributeName.equalsIgnoreCase("schema")) {
						output.setSchema(attributeValue);
					} else if (attributeName.equalsIgnoreCase("encoding")) {
						output.setEncoding(attributeValue);

					} else {
						throw new ExceptionReport(
								"Attribute is not supported: " + attributeName,
								ExceptionReport.INVALID_PARAMETER_VALUE);
					}

				}
			}

		}

	}

	/**
	 * Validates the client request
	 * 
	 * @return True if the input is valid, False otherwise
	 */
	public boolean validate() throws ExceptionReport {
		// Identifier must be specified.
		/*
		 * Only for HTTP_GET: String identifier = getMapValue("identifier");
		 * 
		 * try{ // Specifies if all complex valued output(s) of this process
		 * should be stored by process // as web-accessible resources store =
		 * getMapValue("store").equals("true");
		 *  // Specifies if Execute operation response shall be returned quickly
		 * with status information status =
		 * getMapValue("status").equals("true"); }catch(ExceptionReport e){ //
		 * if parameters "store" or "status" are not included, they default to
		 * false; }
		 *  // just testing if the number of arguments is even... String[]
		 * diArray = getMapValue("DataInputs").split(","); if(diArray.length % 2 !=
		 * 0) { throw new ExceptionReport("Incorrect number of arguments for
		 * parameter dataInputs, please only a even number of parameter values",
		 * ExceptionReport.INVALID_PARAMETER_VALUE); }
		 * 
		 */
		if (!execDom.getExecute().getVersion().equals(SUPPORTED_VERSION)) {
			throw new ExceptionReport("Specified version is not supported.",
					ExceptionReport.INVALID_PARAMETER_VALUE, "version="
							+ getExecute().getVersion());
		}
		// check if the algorithm is in our repository
		if (!RepositoryManager.getInstance().containsAlgorithm(
				getAlgorithmIdentifier())) {
			throw new ExceptionReport(
					"Specified process identifier does not exist",
					ExceptionReport.INVALID_PARAMETER_VALUE,
					getAlgorithmIdentifier());
		}

		// validate if the process can be executed
		ProcessDescriptionType desc = RepositoryManager.getInstance().getProcessDescription(getAlgorithmIdentifier());
		// We need a description of the inputs for the algorithm
		if (desc == null) {
			LOGGER.warn("desc == null");
		}

		// Get the inputdescriptions of the algorithm
		
		if(desc.getDataInputs()!=null){
			InputDescriptionType[] inputDescs = desc.getDataInputs().getInputArray();
		

			// For each input supplied by the client
			for (InputType input : getExecute().getDataInputs().getInputArray()) {
				boolean identifierMatched = false;
				// Try to match the input with one of the descriptions
				for (InputDescriptionType inputDesc : inputDescs) {
					// If found, then process:
					if (inputDesc.getIdentifier().getStringValue().equals(
							input.getIdentifier().getStringValue())) {
						identifierMatched = true;
						// If it is a literal value,
						if (input.getData() != null
								&& input.getData().getLiteralData() != null) {
							// then check if the desription is also of type literal
							if (inputDesc.getLiteralData() == null) {
								throw new ExceptionReport(
										"Inputtype LiteralData is not supported",
										ExceptionReport.INVALID_PARAMETER_VALUE);
							}
							// literalValue.getDataType ist optional
							if (input.getData().getLiteralData().getDataType() != null) {
								if (inputDesc.getLiteralData() != null)
									if (inputDesc.getLiteralData().getDataType() != null)
										if (inputDesc.getLiteralData()
												.getDataType().getReference() != null)
											if (!input
													.getData()
													.getLiteralData()
													.getDataType()
													.equals(
															inputDesc
																	.getLiteralData()
																	.getDataType()
																	.getReference())) {
												throw new ExceptionReport(
														"Specified dataType is not supported "
																+ input
																		.getData()
																		.getLiteralData()
																		.getDataType()
																+ " for input "
																+ input
																		.getIdentifier()
																		.getStringValue(),
														ExceptionReport.INVALID_PARAMETER_VALUE);
											}
							}
						}
						// Excluded, because ProcessDescription validation should be
						// done on startup!
						// else if (input.getComplexValue() != null) {
						// if(ParserFactory.getInstance().getParser(input.getComplexValue().getSchema())
						// == null) {
						// LOGGER.warn("Request validation message: schema attribute
						// null, so the simple one will be used!");
						// }
						// }
						// else if (input.getComplexValueReference() != null) {
						// // we found a complexvalue input, try to get the parser.
						// if(ParserFactory.getInstance().getParser(input.getComplexValueReference().getSchema())
						// == null) {
						// LOGGER.warn("Request validation message: schema attribute
						// null, so the simple one will be used!");
						// }
						// }
						break;
					}
				}
				// if the identifier did not match one of the descriptions, it is
				// invalid
				if (!identifierMatched) {
					throw new ExceptionReport("Input Identifier is not valid: "
							+ input.getIdentifier().getStringValue(),
							ExceptionReport.INVALID_PARAMETER_VALUE,
							"input identifier");
				}
			}
		}
		return true;
	}

	/**
	 * Actually serves the Request.
	 * 
	 * @throws ExceptionReport
	 * @throws InterruptedException 
	 */
	public Response call() throws ExceptionReport {
		try{
	
				// register so that any function that calls ExecuteContextFactory.getContext() gets the instance registered with this thread
			ExecutionContext context = new ExecutionContext(getId());
			ExecutionContextFactory.registerContext(context);
				
			LOGGER.debug("started with execution");
			// parse the input
			InputType[] inputs = new InputType[0];
			if( getExecute().getDataInputs()!=null){
				inputs = getExecute().getDataInputs().getInputArray();
			}
			InputHandler parser = new InputHandler(inputs, getAlgorithmIdentifier());
			
			// we got so far:
			// get the algorithm, and run it with the clients input
		
			/*
			 * IAlgorithm algorithm =
			 * RepositoryManager.getInstance().getAlgorithm(getAlgorithmIdentifier());
			 * returnResults = algorithm.run((Map)parser.getParsedInputLayers(),
			 * (Map)parser.getParsedInputParameters());
			 */
			 algorithm = RepositoryManager.getInstance().getAlgorithm(getAlgorithmIdentifier(), this);
			
			if(algorithm instanceof ISubject){
				ISubject subject = (ISubject) algorithm;
				subject.addObserver(this);
				
			}
			
			
			
			if(algorithm instanceof AbstractTransactionalAlgorithm){
				returnResults = ((AbstractTransactionalAlgorithm)algorithm).run(execDom);
			}
			
//			if (algorithm instanceof IDistributedAlgorithm)
//			{
//				try
//				{
//					returnResults = ((IDistributedAlgorithm) algorithm).run(execDom).getOutputData();
//				}
//				catch (Exception e)
//				{
//					LOGGER.error(e.getMessage());
//					throw new ExceptionReport("Error while executing the embedded process for: " + getAlgorithmIdentifier(), ExceptionReport.NO_APPLICABLE_CODE, e);
//				}
//			}
			if(returnResults==null && !(algorithm instanceof AbstractTransactionalAlgorithm))
			{
				returnResults = algorithm.run(parser.getParsedInputData());
			} 
			/** Check if the thread was cancelled (exception is generated
			 * here to avoid to change the run() inteface of IAlgorithm
			 * but it would be better to throw a CancellationException
			 */
			if(Thread.currentThread().isInterrupted())
			{
				throw new CancellationException("Task Cancelled");
			}
			
		}catch(RuntimeException e) {
			LOGGER.debug("RuntimeException:" + e.getMessage());
			throw new ExceptionReport("Error while executing the embedded process for: " + getAlgorithmIdentifier(), ExceptionReport.NO_APPLICABLE_CODE, e);
		} finally {
			//  you ***MUST*** call this or else you will have a PermGen ClassLoader memory leak due to ThreadLocal use
			ExecutionContextFactory.unregisterContext();
		}
		LOGGER.info("Handled ExecuteRequest successfully for Process: " + getAlgorithmIdentifier());
		StatusType status = StatusType.Factory.newInstance();
		status.setProcessSucceeded("Process successful");
		this.getExecuteResponseBuilder().setStatus(status);
		return new ExecuteResponse(this);
	}

	/**
	 * Gets the identifier of the algorithm the client requested
	 * 
	 * @return An identifier
	 */
	public String getAlgorithmIdentifier() {
		return getExecute().getIdentifier().getStringValue();
	}

	/**
	 * Gets the Execute that is associated with this Request
	 * 
	 * @return The Execute
	 */
	public Execute getExecute() {
		return execDom.getExecute();
	}

	public Map<String, IData> getAttachedResult() {
		return returnResults;
	}

	public boolean isStoreResponse() {
		if (execDom.getExecute().getResponseForm() == null) {
			return false;
		}
		if (execDom.getExecute().getResponseForm().getRawDataOutput() != null) {
			return false;
		}
		return execDom.getExecute().getResponseForm().getResponseDocument()
				.getStoreExecuteResponse();
	}

	public boolean isQuickStatus() {
		if (execDom.getExecute().getResponseForm() == null) {
			return false;
		}
		if (execDom.getExecute().getResponseForm().getRawDataOutput() != null) {
			return false;
		}
		return execDom.getExecute().getResponseForm().getResponseDocument()
				.getStatus();
	}

	public ExecuteResponseBuilder getExecuteResponseBuilder() {
		return this.execRespType;
	}

	public boolean isRawData() {
		if (execDom.getExecute().getResponseForm() == null) {
			return false;
		}
		if (execDom.getExecute().getResponseForm().getRawDataOutput() != null) {
			return true;
		} else {
			return false;
		}
	}

	
	public void update(ISubject subject) {
		Object state = subject.getState();
		LOGGER.info("Update received from Subject, state changed to : " + state);
		StatusType status = StatusType.Factory.newInstance();
		
		int percentage = 0;
		if (state instanceof Integer) {
			percentage = (Integer) state;
			status.addNewProcessStarted().setPercentCompleted(percentage);
		}else if(state instanceof String){
			status.addNewProcessStarted().setStringValue((String)state);
		}
		
		execRespType.setStatus(status);
		try {
			if(this.isStoreResponse()) {
				execRespType.update();
				DatabaseFactory.getDatabase().storeResponse(new ExecuteResponse(this));
			}
			
		} catch (ExceptionReport e) {
			e.printStackTrace();
			LOGGER.debug("Update of process status failed. Reason : " + e.getMessage());
		}
		
	}
	
}
