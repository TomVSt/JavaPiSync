/*
 * (C) Copyright 2014 Tom Van Steertegem (http://www.tomvst.be/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU GENERAL PUBLIC LICENSE
 * (GNU GPL) version 2 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl-2.0.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 */
package javapisync;

import com.dropbox.core.DbxAppInfo;
import com.dropbox.core.DbxAuthFinish;
import com.dropbox.core.DbxClient;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxHost;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.DbxWebAuthNoRedirect;
import com.dropbox.core.DbxWriteMode;
import com.dropbox.core.util.IOUtil;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Locale;

/**
 * @author Tom Van Steertegem
 */
public class JavaPiSync {

    private static String PROGRAM_NAME = "JavaPiSync/1.0";
    private Configuration config = null;
    private String userLocale = null;
    private DbxRequestConfig requestConfig = null;
    private DbxClient dbxClient = null;

    public static void main(String[] args) {
        // No arguments needed
        new JavaPiSync();
    }

    public JavaPiSync() {
        File configFile = new File("config.dat");
        config = new Configuration(configFile);

        // Init dropbox things
        userLocale = Locale.getDefault().toString();
        requestConfig = new DbxRequestConfig(PROGRAM_NAME, userLocale);
        dbxClient = new DbxClient(requestConfig, config.getAccessToken(), DbxHost.Default);

        String accessToken = config.getAccessToken();
        if (accessToken == null || accessToken.length() == 0) {
            // No access token known. Do authorization process...
            config.doAuthorize();
        }

        String syncFolder = config.getSyncFolder();
        if (syncFolder == null || syncFolder.length() == 0) {
            // No syncFolder defined. Ask for one...
            config.setupSync();
        }

        FileStructure syncStructure = new FileStructure(config.getSyncFolder());
        syncStructure.syncLocal();

        // Find deleted files
        ArrayList<DropboxFile> deletedFiles = syncStructure.findDeleted();
        DropboxFile drbf = null;
        for (int x = 0; x < deletedFiles.size(); x++) {
            drbf = deletedFiles.get(x);
            if (drbf.getIsDir()) {
                // Skip directories until second run...
                // This to prevent deletion of the folder with files,
                // and thus generating errors when deleting the files hereafter.
                continue;
            } else {
                deleteFileFromDropbox(drbf);
                deletedFiles.remove(x);
                x--;
            }
            syncStructure.deleteSynced(drbf);
        }
        // Sort directories by length, to nicely delete structures
        ArrayList<DropboxFile> deletedFolders = new ArrayList<DropboxFile>();
        if (deletedFiles.size() > 0) {
            // There are still folders to delete!
            while (deletedFiles.size() > 0) {
                int maxLength = 0;
                int maxIndex = 0;
                for (int x = 0; x < deletedFiles.size(); x++) {
                    if (deletedFiles.get(x).getName().length() > maxLength) {
                        maxLength = deletedFiles.get(x).getName().length();
                        maxIndex = x;
                    }
                }
                deletedFolders.add(deletedFiles.remove(maxIndex));
            }
            for (DropboxFile df : deletedFolders) {
                deleteFolderOnDropbox(df);
                syncStructure.deleteSynced(df);
            }
        }

        // Find changed files
        ArrayList<DropboxFile> changedFiles = syncStructure.findChanged();
        for (DropboxFile df : changedFiles) {
            uploadFileToDropbox(df);
            syncStructure.updateSynced(df);
        }

        // Find new files or directories
        // Due to recursion, parent directories will always be created first
        ArrayList<DropboxFile> newFiles = syncStructure.findNew();
        for (DropboxFile df : newFiles) {
            if (df.getIsDir()) {
                createFolderOnDropbox(df);
            } else {
                uploadFileToDropbox(df);
            }
            syncStructure.addSynced(df);
        }

        syncStructure.writeOut();
    }

    private void deleteFolderOnDropbox(DropboxFile toDelete) {
        try {
            dbxClient.delete(toDelete.getName());
        } catch (DbxException ex) {
            System.out.println("Error uploading to Dropbox: " + ex.getMessage());
        }
    }

    private void createFolderOnDropbox(DropboxFile toCreate) {
        try {
            dbxClient.createFolder(toCreate.getName());
        } catch (DbxException ex) {
            System.out.println("Error uploading to Dropbox: " + ex.getMessage());
        }
    }

    private void deleteFileFromDropbox(DropboxFile toDelete) {
        try {
            dbxClient.delete(toDelete.getName());
        } catch (DbxException ex) {
            System.out.println("Error uploading to Dropbox: " + ex.getMessage());
        }
    }

    private void uploadFileToDropbox(DropboxFile toUpload) {
        InputStream upFile = null;
        try {
            upFile = new FileInputStream(config.syncFolder + toUpload.getName());
            dbxClient.uploadFile(toUpload.getName(), DbxWriteMode.force(), -1, upFile);
        } catch (IOException ex) {
            System.out.println("Error reading from file: " + ex.getMessage());
        } catch (DbxException ex) {
            System.out.println("Error uploading to Dropbox: " + ex.getMessage());
        } finally {
            IOUtil.closeInput(upFile);
        }
    }

    private class Configuration {

        private File configurationFile = null;
        private String apiKey = "";
        private String apiKeySecret = "";
        private String accessToken = "";
        private String syncFolder = "";

        public Configuration(File configurationFile) {
            this.configurationFile = configurationFile;
            if (!this.configurationFile.exists()) {
                readKeys();
            } else {
                readIn();
            }
        }

        private void readKeys() {
            System.out.println("No config file found!");
            System.out.println("------------------------");
            System.out.println("You'll be needing an Dropbox API key, which can be obtained from");
            System.out.println("https://www.dropbox.com/developers/apps");
            System.out.println("------------------------");

            // No config file, so start by reading in the API key
            System.out.print("Please supply the API key: ");

            BufferedReader consoleIn = null;
            try {
                consoleIn = new BufferedReader(new InputStreamReader(System.in));
                apiKey = consoleIn.readLine();
            } catch (IOException ioe) {
                ioe.printStackTrace(System.err);
            }
            if (apiKey == null || apiKey.length() < 15) {
                System.err.println("Invalid API key!");
                System.err.println("Exiting now...");
                System.exit(1);
            }

            // Then, read the API key secret
            System.out.print("Please supply the API key secret: ");
            try {
                consoleIn = new BufferedReader(new InputStreamReader(System.in));
                apiKeySecret = consoleIn.readLine();
            } catch (IOException ioe) {
                ioe.printStackTrace(System.err);
            }
            if (apiKeySecret == null || apiKeySecret.length() < 15) {
                System.err.println("Invalid API key secret!");
                System.err.println("Exiting now...");
                System.exit(1);
            }

            // Write this already out
            writeOut();
        }

        private void doAuthorize() {
            DbxAppInfo appInfo = new DbxAppInfo(this.apiKey, this.apiKeySecret);
            DbxWebAuthNoRedirect webAuth = new DbxWebAuthNoRedirect(requestConfig, appInfo);

            String authorizationCode = "";
            System.out.println("1. Go to " + webAuth.start());
            System.out.println("2. Click \"Allow\" (you might have to log in first).");
            System.out.println("3. Copy the authorization code.");
            System.out.print("Enter the authorization code: ");
            BufferedReader consoleIn = null;
            try {
                consoleIn = new BufferedReader(new InputStreamReader(System.in));
                authorizationCode = consoleIn.readLine();
            } catch (IOException ioe) {
                ioe.printStackTrace(System.err);
            }
            if (authorizationCode == null || authorizationCode.length() == 0) {
                System.err.println("Invalid authorization code!");
                System.err.println("Exiting now...");
                System.exit(1);
            }

            DbxAuthFinish authFinish = null;
            try {
                authFinish = webAuth.finish(authorizationCode);
            } catch (DbxException ex) {
                System.err.println("Error in DbxWebAuth.start: " + ex.getMessage());
                System.err.println("Exiting now...");
                System.exit(1);
            }
            if (authFinish == null) {
                System.err.println("Something went wrong doing the authorization!");
                System.err.println("Exiting now...");
                System.exit(1);
            }

            System.out.println("Authorization completed successfully!");
            System.out.println("- User ID: " + authFinish.userId);
            System.out.println("- Access Token: " + authFinish.accessToken);

            accessToken = authFinish.accessToken;

            // Write this already out
            writeOut();
        }

        public void setupSync() {
            System.out.println("No folder defined to sync!");
            System.out.print("Please supply folder path: ");
            BufferedReader consoleIn = null;
            try {
                consoleIn = new BufferedReader(new InputStreamReader(System.in));
                syncFolder = consoleIn.readLine();
            } catch (IOException ioe) {
                ioe.printStackTrace(System.err);
            }
            if (syncFolder == null || syncFolder.length() == 0) {
                System.err.println("Not a valid folder to sync!");
                System.err.println("Exiting now...");
                System.exit(1);
            }
            File folder = new File(syncFolder);
            if (!folder.exists()) {
                System.err.println("Supplied path does not exist!");
                System.err.println("Exiting now...");
                System.exit(1);
            }
            if (!folder.isDirectory()) {
                System.err.println("Supplied path is not a folder!");
                System.err.println("Exiting now...");
                System.exit(1);
            }

            // All seems fine with the path, saving it
            writeOut();
        }

        public String getApiKey() {
            return this.apiKey;
        }

        public String getApiKeySecret() {
            return this.apiKeySecret;
        }

        public String getApiString() {
            return "{\"key\":\"" + this.apiKey + "\",\"secret\":\"" + this.apiKeySecret + "\"}";
        }

        public String getAccessToken() {
            return this.accessToken;
        }

        public String getSyncFolder() {
            return this.syncFolder;
        }

        private void writeOut() {
            BufferedWriter out = null;
            try {
                out = new BufferedWriter(new FileWriter(configurationFile));
                out.write("API_KEY=" + apiKey);
                out.newLine();
                out.write("API_KEY_SECRET=" + apiKeySecret);
                out.newLine();
                out.write("ACCESS_TOKEN=" + accessToken);
                out.newLine();
                out.write("SYNC_FOLDER=" + syncFolder);
                out.newLine();
                out.close();
                out = null;
            } catch (IOException ioe) {
                ioe.printStackTrace(System.err);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ioe) {
                        ioe.printStackTrace(System.err);
                    }
                    out = null;
                }
            }
        }

        private void readIn() {
            BufferedReader in = null;
            String inputLine;
            try {
                in = new BufferedReader(new FileReader(configurationFile));
                while ((inputLine = in.readLine()) != null) {
                    if (inputLine.startsWith("API_KEY=")) {
                        apiKey = inputLine.substring(8).trim();
                    } else if (inputLine.startsWith("API_KEY_SECRET=")) {
                        apiKeySecret = inputLine.substring(15).trim();
                    } else if (inputLine.startsWith("ACCESS_TOKEN=")) {
                        accessToken = inputLine.substring(13).trim();
                    } else if (inputLine.startsWith("SYNC_FOLDER=")) {
                        syncFolder = inputLine.substring(12).trim();
                    } else {
                        System.err.println("Erroneous config file found!");
                        System.err.println("Exiting now...");
                        System.exit(1);
                    }
                }
                in.close();
                in = null;
            } catch (IOException ioe) {
                ioe.printStackTrace(System.err);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ioe) {
                        ioe.printStackTrace(System.err);
                    }
                    in = null;
                }
            }
        }
    }

    private class FileStructure {

        private File basePath = null;
        private String basePathStr = null;
        private int basePathStrLen = 0;
        private final String FILE_STRUCTURE_PATH = "file_structure.dat";
        private ArrayList<DropboxFile> syncedFiles = new ArrayList<DropboxFile>();
        private ArrayList<DropboxFile> localFiles = new ArrayList<DropboxFile>();

        public FileStructure(String path) {
            basePathStr = path;
            basePathStrLen = path.length();
            basePath = new File(path);

            File fileStructure = new File(FILE_STRUCTURE_PATH);
            if (fileStructure.exists()) {
                readIn();
            }
        }

        public void syncLocal() {
            recurseDir(null);
        }

        private void recurseDir(File dir) {
            File[] files = null;
            if (dir == null) {
                files = basePath.listFiles();
            } else {
                files = dir.listFiles();
            }
            for (File f : files) {
                if (f.isDirectory()) {
                    localFiles.add(new DropboxFile(stripBasePath(f.getPath()), null, true));
                    recurseDir(f);
                } else {
                    localFiles.add(new DropboxFile(stripBasePath(f.getPath()), getHash(f.getAbsolutePath()), false));
                }
            }
        }

        public ArrayList<DropboxFile> findDeleted() {
            ArrayList<DropboxFile> ret = new ArrayList<DropboxFile>();
            DropboxFile tmp = null;
            for (DropboxFile df : syncedFiles) {
                tmp = findNameMatch(df, true);
                if (tmp == null) {
                    // Is a new file!
                    ret.add(df);
                }
            }
            return ret;
        }

        public ArrayList<DropboxFile> findChanged() {
            ArrayList<DropboxFile> ret = new ArrayList<DropboxFile>();
            DropboxFile tmp = null;
            for (DropboxFile df : localFiles) {
                // Filter directories, they can't 'change'
                if (!df.getIsDir()) {
                    tmp = findNameMatch(df, false);
                    if (tmp != null) {
                        if (!df.matches(tmp, false)) {
                            ret.add(df);
                        }
                    }
                }
            }
            return ret;
        }

        public ArrayList<DropboxFile> findNew() {
            ArrayList<DropboxFile> ret = new ArrayList<DropboxFile>();
            DropboxFile tmp = null;
            for (DropboxFile df : localFiles) {
                tmp = findNameMatch(df, false);
                if (tmp == null) {
                    // Is a new file!
                    ret.add(df);
                }
            }
            return ret;
        }

        private DropboxFile findNameMatch(DropboxFile input, boolean local) {
            if (local) {
                for (DropboxFile df : localFiles) {
                    if (df.matches(input, true)) {
                        return df;
                    }
                }
            } else {
                for (DropboxFile df : syncedFiles) {
                    if (df.matches(input, true)) {
                        return df;
                    }
                }
            }
            return null;
        }

        public void deleteSynced(DropboxFile input) {
            DropboxFile df = null;
            for (int x = 0; x < syncedFiles.size(); x++) {
                df = syncedFiles.get(x);
                if (df.matches(input, true)) {
                    syncedFiles.remove(x);
                    return;
                }
            }
        }

        public void updateSynced(DropboxFile input) {
            for (DropboxFile df : syncedFiles) {
                if (df.matches(input, true)) {
                    df.setHash(input.getHash());
                }
            }
        }

        public void addSynced(DropboxFile input) {
            syncedFiles.add(input);
        }

        private String stripBasePath(String input) {
            if (input.startsWith(basePathStr)) {
                return input.substring(basePathStrLen);
            }
            return input;
        }

        private String getHash(String input) {
            String md5 = null;
            try {
                FileInputStream fis = new FileInputStream(input);
                md5 = org.apache.commons.codec.digest.DigestUtils.md5Hex(fis);
                fis.close();
                fis = null;
            } catch (IOException ioe) {
                ioe.printStackTrace(System.err);
            }
            return md5;
        }

        private void readIn() {
            BufferedReader in = null;
            String inputLine;
            try {
                in = new BufferedReader(new FileReader(FILE_STRUCTURE_PATH));
                while ((inputLine = in.readLine()) != null) {
                    syncedFiles.add(new DropboxFile(inputLine));
                }
                in.close();
                in = null;
            } catch (IOException ioe) {
                ioe.printStackTrace(System.err);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ioe) {
                        ioe.printStackTrace(System.err);
                    }
                    in = null;
                }
            }
        }

        public void writeOut() {
            BufferedWriter out = null;
            try {
                out = new BufferedWriter(new FileWriter(FILE_STRUCTURE_PATH));
                for (DropboxFile df : syncedFiles) {
                    out.write(df.getSerialString());
                    out.newLine();
                }
                out.close();
                out = null;
            } catch (IOException ioe) {
                ioe.printStackTrace(System.err);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ioe) {
                        ioe.printStackTrace(System.err);
                    }
                    out = null;
                }
            }
        }
    }

    private class DropboxFile {

        private String name;
        private String hash;
        private boolean isDir = false;

        public DropboxFile(String name, String hash, boolean isDir) {
            this.name = name;
            this.hash = hash;
            this.isDir = isDir;
        }

        public DropboxFile(String serialInput) {
            int tmp = serialInput.indexOf("&&");
            this.name = serialInput.substring(0, tmp);
            serialInput = serialInput.substring(tmp + 2);

            tmp = serialInput.indexOf("&&");
            this.hash = serialInput.substring(0, tmp);
            serialInput = serialInput.substring(tmp + 2);

            if (serialInput.equals("1")) {
                isDir = true;
            }
        }

        public String getName() {
            return this.name;
        }

        public String getHash() {
            return this.hash;
        }

        public void setHash(String hash) {
            this.hash = hash;
        }

        public boolean getIsDir() {
            return this.isDir;
        }

        public String getSerialString() {
            return this.name + "&&" + this.hash + "&&" + ((isDir) ? "1" : "0");
        }

        public boolean matches(DropboxFile input, boolean ignoreHash) {
            if (input.getIsDir() != isDir) {
                // Not same type, so never equal
                return false;
            }
            if (!input.getName().equalsIgnoreCase(name)) {
                // Not the same name, so never equal
                return false;
            }
            if (!ignoreHash) {
                // Hash not ignored
                if (input.getHash() == null || hash == null) {
                    // One of both hashes is null
                    if (!(input.getHash() == null && hash == null)) {
                        // Not both are null, so not equal
                        return false;
                    }
                } else {
                    if (!input.getHash().equals(hash)) {
                        // Hashes both not null, and not equal
                        return false;
                    }
                }
            }
            // All tests were ok, so a match!
            return true;
        }

        @Override
        public String toString() {
            return this.name + ((isDir) ? " [dir]" : "") + " (" + this.hash + ")";
        }
    }

}
