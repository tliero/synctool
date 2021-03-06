/*
 * Copyright 2011, 2012 Tilman Liero
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *      
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package de.tilman.synctool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.RollingFileAppender;
import org.jivesoftware.smack.XMPPException;

import com.martiansoftware.jsap.FlaggedOption;
import com.martiansoftware.jsap.JSAP;
import com.martiansoftware.jsap.JSAPException;
import com.martiansoftware.jsap.JSAPResult;
import com.martiansoftware.jsap.Switch;
import com.martiansoftware.jsap.UnflaggedOption;

import de.tilman.log4j.EmailCollector;
import de.tilman.log4j.JabberAppender;


/**
 * SyncTool synchronized two directories recursively.
 * 
 * @author Tilman Liero
 */
public class SyncTool {

	private final static Logger log = Logger.getLogger(SyncTool.class);

	/**
	 * Defines the different possible operations for two files in the file tree
	 */
	private enum Operation {
		COPY, COPYDESTINATION, DELETE, NONE
	}

	private Connection connection;
	private Statement statement;

	private ResultSet resultSet;
	private PreparedStatement selectFileSql;
	private PreparedStatement insertFileSql;
	private PreparedStatement deleteFileSql;

	private boolean dryRun;
	private boolean hashing;
	private boolean silent;
	private boolean ignoreDirAttribs;
	
	private long filesCompared;
	private long dirsCompared;
	private long dirsCopied;
	private long dirsDeleted;
	private long filesCopied;
	private long filesDeleted;
	
	private List<String> ignoredFiles = null;
	
	public SyncTool(JSAPResult config) {
		
		this.dryRun = config.getBoolean("dry-run");
		if (dryRun)
			log.info("Performing dry-run, no changes to the file system");

		this.silent = config.getBoolean("silent");
		if (silent)
			log.info("Silent logging");

		this.ignoreDirAttribs = config.getBoolean("ignore directory attributes");
		if (ignoreDirAttribs)
			log.info("Ignoring directory attributes");

		this.hashing = config.getBoolean("hashing");
		if (hashing)
			log.info("Using MD5 hashes to compare files");
		
		if (config.getString("ignore file") != null) {
			ignoredFiles = Arrays.asList(config.getStringArray("ignore file"));
		}

		
		try {
			log.info("Connecting to database \"" + config.getString("database file") + "\"");
			Class.forName("org.h2.Driver");

			connection = DriverManager.getConnection("jdbc:h2:file:" + config.getString("database file"), "sa", "");
			connection.setAutoCommit(true);
			statement = connection.createStatement();
			
			resultSet = connection.getMetaData().getTables(null, null, "%", new String[] { "TABLE" });
			if (!resultSet.next()) {
				log.info("Creating database structure");
				statement.execute("CREATE CACHED TABLE Source ("
						+ "id INTEGER GENERATED BY DEFAULT AS IDENTITY (START WITH 1) PRIMARY KEY, "
						+ "path VARCHAR NOT NULL, "
						+ "lastSync TIMESTAMP NOT NULL, "
						+ "UNIQUE (path));");
				statement.execute("CREATE CACHED TABLE File ("
						+ "path VARCHAR NOT NULL, "
						+ "idSource INTEGER NOT NULL, "
						+ "FOREIGN KEY (idSource)"
						+ " REFERENCES Source(id)"
						+ " ON DELETE CASCADE);");
				statement.execute("CREATE INDEX IDX_ID_PATH ON File(path, idSource);");
			}
			
		} catch (Exception e) {
			log.fatal(e.getMessage(), e);
			System.exit(-1);
		}
	}

	/**
	 * Synchronizes two directories specified by their respective paths.
	 * 
	 * @param srcPath the path to the source directory
	 * @param destPath the path to the destination directory
	 */
	public void sync(String srcPath, String destPath) {

		File srcRoot = new File(srcPath);
		File destRoot = new File(destPath);

		if (!srcRoot.isDirectory()) {
			log.fatal(srcRoot + " is not a directory");
			System.exit(-2);
		}
		if (!destRoot.isDirectory()) {
			log.fatal(destRoot + " is not a directory");
			System.exit(-3);
		}

		String canonicalSrcPath = null;
		String canonicalDestPath;

		try {
			canonicalSrcPath = srcRoot.getCanonicalPath();
			canonicalDestPath = destRoot.getCanonicalPath();
			if (canonicalSrcPath.equals(canonicalDestPath)) {
				log.fatal("Source and destination point to the same directory: " + canonicalSrcPath);
				System.exit(-4);
			}
		} catch (IOException ioe) {
			log.fatal(ioe.getMessage(), ioe);
			System.exit(-5);
		}

		try {
			resultSet = statement.executeQuery("SELECT * FROM Source WHERE path='" + canonicalSrcPath + "' LIMIT 1");

			// check the database for the source directory
			Integer sourceId = null;
			if (resultSet.next()) {
				sourceId = resultSet.getInt(1);
				Timestamp lastSync = resultSet.getTimestamp(3);
				log.info("Last sync for source path: " + lastSync);
			} else {
				log.info("Inserting new source path into database: " + canonicalSrcPath);
				if (!dryRun) {
					statement.executeUpdate("INSERT INTO Source (id, path, lastSync) VALUES (NULL, '" + canonicalSrcPath
							+ "', CURRENT_TIMESTAMP)");
					resultSet = statement.executeQuery("SELECT * FROM Source WHERE path='" + canonicalSrcPath + "' LIMIT 1");
					resultSet.next();
					sourceId = resultSet.getInt(1);
				} else {
					sourceId = -1;
				}
			}
			
			// prepare SQL statements
			selectFileSql = connection.prepareCall("SELECT * FROM File WHERE path=? AND idSource=" + sourceId + " LIMIT 1");
			insertFileSql = connection.prepareCall("INSERT INTO File (idSource, path) VALUES (" + sourceId + ", ?)");
			deleteFileSql = connection.prepareCall("DELETE FROM File WHERE idSource=" + sourceId + " AND path=?");

			filesCompared = 0;
			dirsCompared = 0;
			dirsCopied = 0;
			dirsDeleted = 0;
			filesCopied = 0;
			filesDeleted = 0;
			
			log.info("Synchronizing " + srcRoot + " with " + destRoot);
			recurse(srcRoot, destRoot);

			selectFileSql.close();
			insertFileSql.close();
			deleteFileSql.close();

			log.info("Updating source entry in database");
			if (!dryRun)
				statement.executeUpdate("UPDATE Source SET lastSync=CURRENT_TIMESTAMP WHERE id=" + sourceId);

			statement.execute("SHUTDOWN COMPACT");
			statement.close();
			connection.close();
			
			log.info("Subdirectories compared: " + dirsCompared);
			log.info("  Subdirectories copied: " + dirsCopied);
			log.info("  Subdirectories deleted: " + dirsDeleted);
			log.info("Files compared: " + filesCompared);
			log.info("  Files copied: " + filesCopied);
			log.info("  Files deleted: " + filesDeleted);
			
		} catch (SQLException e) {
			log.fatal(e.getMessage(), e);
			System.exit(-6);
		}

	}
	

	private File[] srcFiles;
	private HashMap<String, File> destMap;

	/**
	 * Synchronizes two directories recursively.
	 * 
	 * @param srcDir the source directory
	 * @param destDir the destination directory
	 */
	private void recurse(File srcDir, File destDir) {
		
		log.debug(" get listing for source directory");
		srcFiles = srcDir.listFiles();
		destMap = new HashMap<String, File>();
		log.debug(" get listing for destination directory");
		for (File file : destDir.listFiles()) {
			destMap.put(file.getName(), file);
		}

		ArrayList<File[]> recurseList = new ArrayList<File[]>();

		try {
			log.debug(" sync source side");
			for (int i = srcFiles.length - 1; i >= 0; i--) {
				File srcFile = srcFiles[i];
				srcFiles[i] = null;
				File destFile = destMap.remove(srcFile.getName());
				
				// check for files to ignore 
				if (ignoredFiles != null) {
					if (ignoredFiles.contains(srcFile.getPath())) {
						log.info("  Ignoring file " + srcFile.getPath());
						continue;
					}
				}

				// check synchronization history
				log.debug("  get source history from database");
				selectFileSql.setString(1, srcFile.getCanonicalPath());
				resultSet = selectFileSql.executeQuery();
				boolean history = false;
				if (resultSet.next())
					history = true;

				// determine what to do and do it
				log.debug("  get operation");
				Operation operation = getOperation(srcFile, destFile, history);
				log.debug("  synchronize");
				if (operation == Operation.COPYDESTINATION)
					syncFileToDirectory(destFile, srcDir, Operation.COPY);
				else
					syncFileToDirectory(srcFile, destDir, operation);

				// if the file is a directory and has not been copied or
				// deleted, add for recursion
				if (srcFile.isDirectory() && operation == Operation.NONE) {
					log.debug("  adding directory for recursion");
					if (destFile == null) {
						destFile = new File(destDir, srcFile.getName());
					}
					recurseList.add(new File[] { srcFile, destFile });
				}
			}

			// opposite direction: process remaining files from destination
			log.debug(" sync destination side");
			for (File destFile : destMap.values()) {
				if (ignoredFiles.contains(destFile.getPath())) {
					log.info("  Ignoring file " + destFile.getPath());
					continue;
				}
				
				// check synchronization history
				selectFileSql.setString(1, new File(srcDir, destFile.getName()).getCanonicalPath());
				log.debug("  get history");
				resultSet = selectFileSql.executeQuery();
				boolean history = false;
				if (resultSet.next())
					history = true;

				log.debug("  synchronize");
				syncFileToDirectory(destFile, srcDir, getOperation(destFile, null, history));
			}

			// recurse all directories that have not been deleted or entirely
			// copied
			for (File[] recurseDir : recurseList) {
				if (!silent)
					log.info("Entering directory " + recurseDir[0]);
				recurse(recurseDir[0], recurseDir[1]);
			}

		} catch (Exception e) {
			log.fatal(e.getMessage(), e);
			System.exit(-7);
		}
	}

	/**
	 * Conducts the specified operation for the file.
	 * 
	 * @param file the file to be processed
	 * @param directory the target directory
	 * @param operation the operation to be executed
	 */
	private void syncFileToDirectory(File file, File directory, Operation operation) {

		try {
			if (operation == Operation.NONE) {
				if (!silent)
					log.info("No operation for " + file);
				return;
			} else if (operation == Operation.COPY) {
				if (file.isDirectory()) {
					log.info("Copying directory " + file);
					if (!dryRun)
						FileUtils.copyDirectoryToDirectory(file, directory);
					dirsCopied++;
				} else {
					log.info("Copying file " + file);
					if (!dryRun)
						FileUtils.copyFileToDirectory(file, directory);
					filesCopied++;
				}
				return;
			} else if (operation == Operation.DELETE) {
				if (file.isDirectory()) {
					log.info("Deleting directory " + file);
					if (!dryRun)
						FileUtils.deleteDirectory(file);
					dirsDeleted++;
				} else {
					log.info("Deleting file " + file);
					if (!dryRun)
						file.delete();
					filesDeleted++;
				}
				return;
			}
		} catch (IOException ioe) {
			log.fatal(ioe.getMessage(), ioe);
			System.exit(-8);
		}
	}

	/**
	 * Determines what to do with two files at the same place in the file tree
	 * on the source and the destination. This method also updates the database
	 * for the source file.
	 */
	private Operation getOperation(File srcFile, File destFile, boolean history) throws SQLException, IOException {

		if (destFile != null && destFile.exists()) {

			if (!history) {
				insertFileSql.setString(1, srcFile.getCanonicalPath());
				if (!dryRun)
					insertFileSql.execute();
			}

			if (srcFile.isDirectory()) {
				dirsCompared++;
				if (!dryRun && !ignoreDirAttribs) {
					if (srcFile.lastModified() != destFile.lastModified()
//							|| srcFile.canExecute() != destFile.canExecute()
//							|| srcFile.canRead() != destFile.canRead()
//							|| srcFile.canWrite() != destFile.canWrite()
							) {
						log.info("Setting attributes for " + destFile);
						destFile.setLastModified(srcFile.lastModified());
//						destFile.setExecutable(srcFile.canExecute());
//						destFile.setReadable(srcFile.canRead());
//						destFile.setWritable(srcFile.canWrite());
					}
				}
				return Operation.NONE;
			}
			
			filesCompared++;

			if (consideredEqual(srcFile, destFile))
				return Operation.NONE;

			if (srcFile.lastModified() > destFile.lastModified())
				return Operation.COPY; // copy source file

			return Operation.COPYDESTINATION; // copy destination file
		}

		// if the file exists in the history, it has been deleted on the
		// target side and should also be deleted on the source side
		if (history) {
			deleteFileSql.setString(1, srcFile.getCanonicalPath());
			if (!dryRun)
				deleteFileSql.execute();
			return Operation.DELETE;
		}

		// if the file is not present in the synchronization history, it
		// has been added on the source side and should be copied
		insertFileSql.setString(1, srcFile.getCanonicalPath());
		if (!dryRun)
			insertFileSql.execute();
		return Operation.COPY; // copy source file

	}

	/**
	 * Determines whether two files at the same place in the file tree are
	 * considered to be equal under the given parameters.
	 * @throws IOException 
	 * @throws FileNotFoundException 
	 */
	private boolean consideredEqual(File src, File dest) throws FileNotFoundException, IOException {
		// TODO add a certain amount to the source timestamp, if defined by parameter
		// TODO allow a certain difference for the timestamps, if defined by parameter
		if ((src.lastModified() == dest.lastModified()) && (src.length() == dest.length())) {
			if (!hashing)
				return true;
			if (DigestUtils.md5Hex(new FileInputStream(src)).equals(DigestUtils.md5Hex(new FileInputStream(dest))))
				return true;
		}
		return false;
	}
	

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("%d{ISO8601} - %m%n")));
		log.info("Starting SyncTool version 1.43");

		JSAP jsap = new JSAP();

		try {
			UnflaggedOption sourceOption = new UnflaggedOption("source path").setStringParser(JSAP.STRING_PARSER).setRequired(
					true);
			sourceOption.setHelp("the source path");
			jsap.registerParameter(sourceOption);

			UnflaggedOption destinationOption = new UnflaggedOption("destination path").setStringParser(JSAP.STRING_PARSER)
					.setRequired(true);
			destinationOption.setHelp("the destination path");
			jsap.registerParameter(destinationOption);

			FlaggedOption dbFileOption = new FlaggedOption("database file").setStringParser(JSAP.STRING_PARSER).setLongFlag(
					"dbfile").setShortFlag('f').setDefault("synctool");
			dbFileOption.setHelp("the path to the database file to use");
			jsap.registerParameter(dbFileOption);

			FlaggedOption logfileOption = new FlaggedOption("logfile").setStringParser(JSAP.STRING_PARSER)
					.setLongFlag("logfile").setShortFlag('l');
			logfileOption.setHelp("the path for a logfile to write");
			jsap.registerParameter(logfileOption);

			FlaggedOption smtpUser = new FlaggedOption("SMTP user").setStringParser(JSAP.STRING_PARSER).setLongFlag(
					"smtpuser").setShortFlag('t');
			smtpUser.setHelp("the SMTP user for e-mail reporting");
			jsap.registerParameter(smtpUser);

			FlaggedOption smtpPassword = new FlaggedOption("SMTP password").setStringParser(JSAP.STRING_PARSER).setLongFlag(
					"smtppassword").setShortFlag('w');
			smtpPassword.setHelp("the SMTP password used for sending the e-mail report");
			jsap.registerParameter(smtpPassword);

			FlaggedOption emailAddress = new FlaggedOption("e-mail address").setStringParser(JSAP.STRING_PARSER).setLongFlag(
					"email").setShortFlag('e');
			emailAddress.setHelp("send logging output as jabber message to the given address");
			jsap.registerParameter(emailAddress);

			FlaggedOption jabberAddress = new FlaggedOption("jabber address").setStringParser(JSAP.STRING_PARSER).setLongFlag(
					"jabber").setShortFlag('j');
			jabberAddress.setHelp("send logging output as jabber message to the given address");
			jsap.registerParameter(jabberAddress);

			FlaggedOption jabberServer = new FlaggedOption("jabber server").setStringParser(JSAP.STRING_PARSER).setLongFlag(
					"server").setShortFlag('r');
			jabberServer.setHelp("the jabber server to connect to");
			jsap.registerParameter(jabberServer);

			FlaggedOption jabberUser = new FlaggedOption("jabber user").setStringParser(JSAP.STRING_PARSER).setLongFlag(
					"user").setShortFlag('u');
			jabberUser.setHelp("the jabber user name used for logging in to the server");
			jsap.registerParameter(jabberUser);

			FlaggedOption jabberPassword = new FlaggedOption("jabber password").setStringParser(JSAP.STRING_PARSER).setLongFlag(
					"password").setShortFlag('p');
			jabberPassword.setHelp("the jabber password used for logging in to the server");
			jsap.registerParameter(jabberPassword);

			FlaggedOption ignoredFile = new FlaggedOption("ignore file").setStringParser(JSAP.STRING_PARSER).setLongFlag(
					"ignore").setShortFlag('g').setAllowMultipleDeclarations(true);
			jabberPassword.setHelp("path to a file that should be ignored during synchronization");
			jsap.registerParameter(ignoredFile);

			Switch dryRunSwitch = new Switch("dry-run").setLongFlag("dry-run").setShortFlag('d');
			dryRunSwitch.setHelp("perform a trial run with no changes made");
			jsap.registerParameter(dryRunSwitch);

			Switch hashingSwitch = new Switch("hashing").setLongFlag("hashing").setShortFlag('h');
			hashingSwitch.setHelp("generate MD5 file hashes for exact comparison");
			jsap.registerParameter(hashingSwitch);

			Switch rollingSwitch = new Switch("rolling-logfile").setLongFlag("rolling-logfile").setShortFlag('o');
			rollingSwitch.setHelp("generate a rolling logfile with a maximum size of 10 MB");
			jsap.registerParameter(rollingSwitch);

			Switch ignoreDirAttribsSwitch = new Switch("ignore directory attributes").setLongFlag("ignore-directory-attributes").setShortFlag('i');
			ignoreDirAttribsSwitch.setHelp("do not copy attributes for directories");
			jsap.registerParameter(ignoreDirAttribsSwitch);

			Switch silentSwitch = new Switch("silent").setLongFlag("silent").setShortFlag('s');
			silentSwitch.setHelp("do not print \"Entering directory\" and \"No operation\" messages");
			jsap.registerParameter(silentSwitch);

			FlaggedOption checkFileExists = new FlaggedOption("checkfile").setStringParser(JSAP.STRING_PARSER).setLongFlag(
			"check-file-exists");
			checkFileExists.setHelp("perform synchronization only if the given file exists");
			jsap.registerParameter(checkFileExists);

			Switch debugSwitch = new Switch("debug").setLongFlag("debug");
			debugSwitch.setHelp("print debug messages");
			jsap.registerParameter(debugSwitch);

			Switch helpSwitch = new Switch("help").setLongFlag("help").setShortFlag('?');
			helpSwitch.setHelp("print help and exit");
			jsap.registerParameter(helpSwitch);

		} catch (JSAPException e) {
			log.fatal(e.getMessage());
			System.exit(-1002);
		}
		
		JSAPResult config = jsap.parse(args);
		
		if (!config.success() || config.getBoolean("help")) {
			for (java.util.Iterator errs = config.getErrorMessageIterator(); errs.hasNext();) {
				log.error("Error: " + errs.next());
			}

			log.error("Usage: java -jar synctool.jar " + jsap.getUsage() + "\n\n" + jsap.getHelp());
			System.exit(-1003);
		}
		
		if (config.getBoolean("debug")) {
			log.setLevel(Level.DEBUG);
		}
		else {
			log.setLevel(Level.INFO);
		}
		log.info("Log level set to " + log.getLevel());
		
		if (config.getString("logfile") != null) {
			try {
				if (config.getBoolean("rolling-logfile")) {
					BasicConfigurator.configure(new RollingFileAppender(new PatternLayout("%d{ISO8601} - %m%n"), config.getString("logfile")));
					log.info("Logging to rolling logfile " + config.getString("logfile"));
				}
				else {
					BasicConfigurator.configure(new FileAppender(new PatternLayout("%d{ISO8601} - %m%n"), config.getString("logfile")));
					log.info("Logging to " + config.getString("logfile"));
				}
			} catch (IOException ioe) {
				log.fatal(ioe.getMessage(), ioe);
				System.exit(-1004);
			}
		}
		
		if (config.getString("SMTP user") != null) {
			BasicConfigurator.configure(new EmailCollector(200, config.getString("SMTP user"), config.getString("SMTP password"), config.getString("e-mail address"), "SyncTool Report"));
			log.info("Prepared e-mail report for " + config.getString("e-mail address"));
			
		}
		
		if (config.getString("jabber address") != null) {
			try {
				BasicConfigurator.configure(new JabberAppender(config.getString("jabber address"), config.getString("jabber server"), config.getString("jabber user"), config.getString("jabber password")));
			} catch (XMPPException xe) {
				log.fatal(xe.getMessage(), xe);
				System.exit(-1005);
			}
			log.info("Logging to " + config.getString("jabber address"));
		}
		
		if (config.getString("checkfile") != null) {
			if (new File(config.getString("checkfile")).exists() == false) {
				log.error("The file " + config.getString("checkfile") + " does not exist. Stopping synchronization.");
				System.exit(-1006);
			}
		}
		
		SyncTool syncTool = new SyncTool(config);
		syncTool.sync(config.getString("source path"), config.getString("destination path"));
		
		// shutting down the logger will trigger the generation of the e-mail report
		LogManager.shutdown();
	}
}
