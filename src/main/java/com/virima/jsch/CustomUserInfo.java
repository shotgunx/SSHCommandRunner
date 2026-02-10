package com.virima.jsch;

import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

public class CustomUserInfo implements UserInfo, UIKeyboardInteractive{
	
	final String password;
	
	public CustomUserInfo(String password) {
		this.password = password;
	}

    public boolean promptYesNo(String str){
	return false;
	}
	 
	public String getPassphrase(){ return null; }

    @Override
    public String getPassword() {
        return password;
    }

    public boolean promptPassphrase(String message){ return false; }
	public boolean promptPassword(String message){
	return false;
	}
	
	 
	public String[] promptKeyboardInteractive(String destination,
	String name,
	String instruction,
	String[] prompt,
	boolean[] echo){
	
		String [] response = new String[1];
		response[0] = password;
		return response;
	}
	@Override
	public void showMessage(String arg0) {
		
		
	}
}
