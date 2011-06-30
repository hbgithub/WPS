package org.n52.wps.commons.context;

import java.util.UUID;

public class ExecutionContext {
    
	private String tempFolderName;
	
	public ExecutionContext(){
		tempFolderName = UUID.randomUUID().toString();
	}
	
    public ExecutionContext(String id2) {
    	this.tempFolderName = id2;
	}

	public String getTempDirectoryPath() {
    	
        return System.getProperty("java.io.tmpdir")+tempFolderName;
    }

    
    // add more as needed...
}
