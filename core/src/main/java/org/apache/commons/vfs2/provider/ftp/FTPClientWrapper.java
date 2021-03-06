/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.vfs2.provider.ftp;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.UserAuthenticationData;
import org.apache.commons.vfs2.provider.GenericFileName;
import org.apache.commons.vfs2.provider.URLFileName;
import org.apache.commons.vfs2.provider.sftp.SftpConstants;
import org.apache.commons.vfs2.util.UserAuthenticatorUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * A wrapper to the FTPClient to allow automatic reconnect on connection loss.<br />
 * I decided to not to use eg. noop() to determine the state of the connection to avoid
 * unnecesary server round-trips.
 * @author <a href="http://commons.apache.org/vfs/team-list.html">Commons VFS team</a>
 */
class FTPClientWrapper implements FtpClient
{
    private static final Log log = LogFactory.getLog(FTPClientWrapper.class);
    private final GenericFileName root;
    private final FileSystemOptions fileSystemOptions;
    private Integer defaultTimeout = null;

    private FTPClient ftpClient;

    FTPClientWrapper(final GenericFileName root, final FileSystemOptions fileSystemOptions, Integer defaultTimeout)
            throws FileSystemException {
        this.root = root;
        this.fileSystemOptions = fileSystemOptions;
        this.defaultTimeout = defaultTimeout;
        getFtpClient(); // fail-fast
    }

    FTPClientWrapper(final GenericFileName root, final FileSystemOptions fileSystemOptions) throws FileSystemException {
        this(root, fileSystemOptions, null);
    }

    public GenericFileName getRoot()
    {
        return root;
    }

    public FileSystemOptions getFileSystemOptions()
    {
        return fileSystemOptions;
    }

    private FTPClient createClient() throws FileSystemException
    {
        final GenericFileName rootName = getRoot();
        Map<String,String>mParams = null;
        if(rootName instanceof URLFileName){
        	mParams = getQueryParams((URLFileName)rootName);
        }
        UserAuthenticationData authData = null;
        try
        {
	        authData = UserAuthenticatorUtils.authenticate(fileSystemOptions, FtpFileProvider.AUTHENTICATOR_TYPES);

	        char[] username = UserAuthenticatorUtils.getData(authData, UserAuthenticationData.USERNAME,
	                                                         UserAuthenticatorUtils.toChar(rootName.getUserName()));

	        char[] password = UserAuthenticatorUtils.getData(authData, UserAuthenticationData.PASSWORD,
	                                                         UserAuthenticatorUtils.toChar(rootName.getPassword()));

            if (mParams == null) {

                return FtpClientFactory.createConnection(rootName.getHostName(), rootName.getPort(), username, password,
                                                         rootName.getPath(), getFileSystemOptions(), defaultTimeout);
            } else {

                return FtpClientFactory.createConnection(rootName.getHostName(), rootName.getPort(), username, password,
                                                         rootName.getPath(), getFileSystemOptions(),
                                                         mParams.get(SftpConstants.PROXY_SERVER),
                                                         mParams.get(SftpConstants.PROXY_PORT),
                                                         mParams.get(SftpConstants.PROXY_USERNAME),
                                                         mParams.get(SftpConstants.PROXY_PASSWORD),
                                                         mParams.get(SftpConstants.TIMEOUT),
                                                         mParams.get(SftpConstants.RETRY_COUNT), defaultTimeout);
            }
        }
        finally
        {
            UserAuthenticatorUtils.cleanup(authData);
        }
    }

    private Map<String, String> getQueryParams(URLFileName urlFileName){
    	Map<String, String>mQueryParams = new HashMap<String,String>();
    	String strQuery = urlFileName.getQueryString();
    	if(strQuery != null && !strQuery.equals("")){
    		for(String strParam:strQuery.split("&")){
    			String [] arrParam = strParam.split("=");
    			if(arrParam.length >= 2){
    				mQueryParams.put(arrParam[0], arrParam[1]);
    			}
    		}
    	}
    	return mQueryParams;
    }

    private synchronized FTPClient getFtpClient() throws FileSystemException
    {
        if (ftpClient == null)
        {
            ftpClient = createClient();
        }
        if (root.getURI().contains("vfs.passive=true") &&
            ftpClient.getDataConnectionMode() == FTPClient.ACTIVE_LOCAL_DATA_CONNECTION_MODE) {
            if (log.isDebugEnabled()) {
                log.debug("FTP Client is entering into passive mode.");
            }
            ftpClient.enterLocalPassiveMode();
        }

        return ftpClient;
    }

    public boolean isConnected() throws FileSystemException
    {
        return ftpClient != null && ftpClient.isConnected();
    }

    public void disconnect() throws IOException
    {
        try
        {
            getFtpClient().disconnect();
        }
        finally
        {
            ftpClient = null;
        }
    }

    public FTPFile[] listFiles(String relPath) throws IOException
    {
        try
        {
            // VFS-210: return getFtpClient().listFiles(relPath);
            FTPFile[] files = listFilesInDirectory(relPath);
            return files;
        }
        catch (IOException e)
        {
            disconnect();

            FTPFile[] files = listFilesInDirectory(relPath);
            return files;
        }
    }

    private FTPFile[] listFilesInDirectory(String relPath) throws IOException
    {
        FTPFile[] files;

        // VFS-307: no check if we can simply list the files, this might fail if there are spaces in the path
        files = getFtpClient().listFiles(relPath);
        if (FTPReply.isPositiveCompletion(getFtpClient().getReplyCode()))
        {
            return files;
        }

        // VFS-307: now try the hard way by cd'ing into the directory, list and cd back
        // if VFS is required to fallback here the user might experience a real bad FTP performance
        // as then every list requires 4 ftp commands.
        String workingDirectory = null;
        if (relPath != null)
        {
            workingDirectory = getFtpClient().printWorkingDirectory();
            if (!getFtpClient().changeWorkingDirectory(relPath))
            {
                return null;
            }
        }

        files = getFtpClient().listFiles();

        if (relPath != null && !getFtpClient().changeWorkingDirectory(workingDirectory))
        {
            throw new FileSystemException("vfs.provider.ftp.wrapper/change-work-directory-back.error",
                    workingDirectory);
        }
        return files;
    }

    public boolean removeDirectory(String relPath) throws IOException
    {
        try
        {
            return getFtpClient().removeDirectory(relPath);
        }
        catch (IOException e)
        {
            disconnect();
            return getFtpClient().removeDirectory(relPath);
        }
    }

    public boolean deleteFile(String relPath) throws IOException
    {
        try
        {
            return getFtpClient().deleteFile(relPath);
        }
        catch (IOException e)
        {
            disconnect();
            return getFtpClient().deleteFile(relPath);
        }
    }

    public boolean rename(String oldName, String newName) throws IOException
    {
        try
        {
            return getFtpClient().rename(oldName, newName);
        }
        catch (IOException e)
        {
            disconnect();
            return getFtpClient().rename(oldName, newName);
        }
    }

    public boolean makeDirectory(String relPath) throws IOException
    {
        try
        {
            return getFtpClient().makeDirectory(relPath);
        }
        catch (IOException e)
        {
            disconnect();
            return getFtpClient().makeDirectory(relPath);
        }
    }

    public boolean completePendingCommand() throws IOException
    {
        if (ftpClient != null)
        {
            return getFtpClient().completePendingCommand();
        }

        return true;
    }

    public InputStream retrieveFileStream(String relPath) throws IOException
    {
        try
        {
            return getFtpClient().retrieveFileStream(relPath);
        }
        catch (IOException e)
        {
            disconnect();
            return getFtpClient().retrieveFileStream(relPath);
        }
    }

    public InputStream retrieveFileStream(String relPath, long restartOffset) throws IOException
    {
        try
        {
            FTPClient client = getFtpClient();
            client.setRestartOffset(restartOffset);
            return client.retrieveFileStream(relPath);
        }
        catch (IOException e)
        {
            disconnect();

            FTPClient client = getFtpClient();
            client.setRestartOffset(restartOffset);
            return client.retrieveFileStream(relPath);
        }
    }

    public OutputStream appendFileStream(String relPath) throws IOException
    {
        try
        {
            return getFtpClient().appendFileStream(relPath);
        }
        catch (IOException e)
        {
            disconnect();
            return getFtpClient().appendFileStream(relPath);
        }
    }

    public OutputStream storeFileStream(String relPath) throws IOException
    {
        try
        {
            return getFtpClient().storeFileStream(relPath);
        }
        catch (IOException e)
        {
            disconnect();
            return getFtpClient().storeFileStream(relPath);
        }
    }

    public boolean abort() throws IOException
    {
        try
        {
            // imario@apache.org: 2005-02-14
            // it should be better to really "abort" the transfer, but
            // currently I didnt manage to make it work - so lets "abort" the hard way.
            // return getFtpClient().abort();

            disconnect();
            return true;
        }
        catch (IOException e)
        {
            disconnect();
        }
        return true;
    }

    public String getReplyString() throws IOException
    {
        return getFtpClient().getReplyString();
    }
}
