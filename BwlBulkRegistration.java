/**
 * BwlBulkRegistration
 * 
 * By using Blueworks Live's REST API this application will register a list 
 * of given users for a specific Blueworks Live account.
 * 
 * @author Martin Westphal, westphal@de.ibm.com
 * @version 1.0
 */
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.xml.bind.DatatypeConverter;

import org.apache.commons.io.FileUtils;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;

/**
 * 
 * Compile:
 *    javac -cp .;commons-io-2.4.jar;wink-json4j-1.3.0.jar BwlBulkRegistration.java
 * 
 * Run it:
 *    java -cp .;commons-io-2.4.jar;wink-json4j-1.3.0.jar BwlBulkRegistration <user> <password> <account> <userListFile>
 * 
 */
public class BwlBulkRegistration {

    // --- The Blueworks Live server info and login
    private final static String REST_API_SERVER = "https://www.blueworkslive.com";
    private final static String REST_API_CALL_AUTH = "/api/Auth";
    private final static String REST_API_AUTH_VERSION = "20110917";
    private static String REST_API_USERNAME = "";
    private static String REST_API_PASSWORD = "";
    private static String REST_API_ACCOUNT_NAME = "";
    private static String serviceProviderAddress = null;

    // --- User list file
    private static String USER_LIST_FILE_NAME = "";

    // --- Configuration
    private static String DEFAULT_ROLE = "viewer";
    private static boolean DEFAULT_ADMIN = false;
    private static boolean CHECK_ONLY = false;
    
    // --- Usage
    private static String USAGE = "Usage: BwlBulkRegistration <user> <password> <account> <userListFile> [optional_arguments]\n"
    		+ "Optional arguments:\n"
    		+ "  -h          This help message\n"
    		+ "  -r <role>   The default role for a new user, default="+DEFAULT_ROLE+"\n"
    		+ "  -a          Make new users to admin by default\n"
    		+ "  -c          Check only user list file but do not actually register users\n"
    		;
    

    public static void main(String[] args) {
    	int i = 4;
    	String arg;
    	if (args.length < i) printErrorAndExit("missing command line arguments, "+i+" arguments required");
    	REST_API_USERNAME = args[0];
    	REST_API_PASSWORD = args[1];
    	REST_API_ACCOUNT_NAME = args[2];
    	USER_LIST_FILE_NAME = args[3];
        
    	while (i < args.length && args[i].startsWith("-")) {
            arg = args[i++];
    		if (arg.equals("-h")) printErrorAndExit("");
    		else if (arg.equals("-r")) {
                if (i < args.length) {
                	String proposedRole = args[i++];
                	DEFAULT_ROLE = checkRole(proposedRole);
                	if (DEFAULT_ROLE == null) printErrorAndExit("role '"+proposedRole+"' given with option -r is unknown"); 
                }
                else printErrorAndExit("option -d requires a string argument"); 
            }
    		else if (arg.equals("-r")) {
                DEFAULT_ADMIN = true;
            }
    		else if (arg.equals("-c")) {
    			CHECK_ONLY = true;
            }
    		else  {
    			printErrorAndExit("unknown command line option "+arg);
            }
    	}
    	
        try {
        	String userDat = readUsers(USER_LIST_FILE_NAME);
			if (!CHECK_ONLY) serviceProviderAddress = getServiceProviderAddress();

			int lineCount = 0, userCount = 0, validCount = 0, registerCount = 0;
            BufferedReader rdr = new BufferedReader(new StringReader(userDat));
            for (String line = rdr.readLine(); line != null; line = rdr.readLine()) {
            	line = line.trim();
            	lineCount++;
            	if (!line.isEmpty()) {
            		userCount++;
            		String[] field = line.split(",");
    				JSONObject user = new JSONObject();
    				user.put("username", field[0].trim());
    				user.put("fullname", "");
    				user.put("license", DEFAULT_ROLE);
    				user.put("admin", DEFAULT_ADMIN);
    				
            		String error = null;
            		if (field.length>1) {
            			user.put("fullname", field[1].trim());
            			if (field.length>2) {
            				String role = checkRole(field[2].trim());
            				if (role == null) error = "unknown role '"+field[2].trim()+"'";
            				else {
            					user.put("license", role);
            					if (field.length>3) {
                    				user.put("admin", Boolean.parseBoolean(field[3].trim()));
            					}
                			}
            			}
            		}
            		if (error != null) printError("could not parse line "+lineCount+" ("+error+"):  "+line);
            		else {
            			validCount++;
            			printResult(">REGISTRATION-REQUEST #"+userCount+" for user "+user.getString("username")+": "+user.toString());
            			if (!CHECK_ONLY) {
            				if (registerUser(user)) registerCount++;
            			}
            		}
            	}
            }
            rdr.close();
            printResult("=============== SUMMARY ===============");
            printResult(" lines processed : "+lineCount);
            printResult(" user entries    : "+userCount);
            printResult(" valid entries   : "+validCount);
            printResult(" registered users: "+registerCount);
            printResult("=======================================");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Print result.
     * 
     * @param text
     */
    private static void printResult(String text) {
    	System.out.println(text);
    }
    /**
     * Print error.
     * 
     * @param text
     */
    private static void printError(String text) {
    	System.err.println("ERROR: "+text);
    }


    /**
     * Call this method to print out an error message during command line parsing,
     * together with the USAGE information and exit.
     * Use an empty message to get USAGE only.
     * 
     * @param message the error message to print
     */
    private static void printErrorAndExit (String message) {
        if (message.length() > 0) System.err.println("ERROR: "+message);
        System.err.println(USAGE);
        System.exit(1);
    }

    /**
     * Checks a given role to match available Blueworks Live roles.
     * Partial begin of role name such as the first character is sufficient.
     * 
     * @param Blueworks Live role
     * @return normalized role name in lower case or null if role not identified
     */
    private static String checkRole (String role) {
    	String[] roles = {"editor","contributor","viewer"};
    	for (String aRole : roles) {
			if (aRole.startsWith(role.toLowerCase())) return aRole;
		}
    	return null;
    }

    /**
     * Read user list from a given file.
     * 
     * @param filename The file that contains the user list.
     * @return the 'from' date as formatted text
     */
    private static String readUsers(String filename) {
    	String userData = "";
    	try {
    		userData = FileUtils.readFileToString(new File(filename));
		} catch (IOException e1) {
			printErrorAndExit("could not read file "+filename);
		}
    	return userData;
    }
    
    /**
     * Use the API resource "User provisioning" to register a new user.
     * 
     * @param json object describing user, for example: {"username":"test@ibm.com","fullname":"Tom Miller","license":"EDITOR","admin":false}
     * @throws IOException 
     */
    private static boolean registerUser (JSONObject user) throws IOException  {
    	boolean success = false;
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append((serviceProviderAddress == null) ? REST_API_SERVER: serviceProviderAddress);
        urlBuilder.append("/scr/api/provision/user/");
        urlBuilder.append("?account=").append(REST_API_ACCOUNT_NAME);

        HttpURLConnection restApiURLConnection = getRestApiConnection(urlBuilder.toString(), "PUT");
        restApiURLConnection.setRequestProperty("Version", "1.0");
        restApiURLConnection.setRequestProperty("Content-Type", "application/json");
        restApiURLConnection.setDoOutput(true);
        OutputStreamWriter out = new OutputStreamWriter(restApiURLConnection.getOutputStream());
        out.write(user.toString());
        out.close();
        
    	InputStream restApiStream = null;
    	int responseCode = restApiURLConnection.getResponseCode();
        try {
        	/*
        	 * Here are example results for 
        	 * code=200 - OK: {"license": "EDITOR", "admin": false, "active": true, "fullname": "", "username": "test@ibm.com"}
        	 * code=400 - Bad Request: {"message": "The provided user name is already a member of this account."}
        	 * code=400 - Bad Request: {"message":"The provided user name is not valid. Ensure the user name is provided and is a valid email address."}
        	 * code=400 - Bad Request: {"message":"There are no remaining licenses for the requested license type."}
        	 * code=400 - Bad Request: {"message":"The provided email address belongs to a domain which is blocked by the account."}
        	 */
        	if (responseCode == HttpURLConnection.HTTP_OK) {
        		restApiStream = restApiURLConnection.getInputStream();
                JSONObject result = new JSONObject(restApiStream);
                printResult("<REGISTRATION-RESULT successfully registered user "+user.getString("username")+": "+result.toString());
                success=true;
        	} else {
        		restApiStream = restApiURLConnection.getErrorStream();
        		if (restApiStream != null) {
                    JSONObject result = new JSONObject(restApiStream);
                    printError("<REGISTRATION-ERROR for user "+user.getString("username")+" (Code="+responseCode+"): "+restApiURLConnection.getResponseMessage()+" "+result.toString());
        		} else {
                    printError("<REGISTRATION-ERROR for user "+user.getString("username")+" (Code="+responseCode+"): "+restApiURLConnection.getResponseMessage());
        		}
        		//System.err.println("Request: "+urlBuilder.toString());
        	}
        
        } catch (Exception e) {
        	e.printStackTrace();
    	} finally {
            // Clean up any streams we have opened.
    		if (restApiStream != null) restApiStream.close();
        }
        return success;
    }

    /**
     * Setup the connection to a REST API including handling the Basic Authentication request headers that must be
     * present on every API call.
     * 
     * @param apiCall The URL string indicating the API call and parameters.
     * @param method The HTTP method this API uses (e.g. "GET" or "POST").
     * 
     * @return the open connection
     */
    public static HttpURLConnection getRestApiConnection(String apiCall, String method) throws IOException {

        // Call the provided api on the Blueworks Live server
        URL restApiUrl = new URL(apiCall);
        HttpURLConnection authApiURLConnection = (HttpURLConnection) restApiUrl.openConnection();
        authApiURLConnection.setRequestMethod(method);

        // Add the HTTP Basic authentication header which should be present on every API call.
        addAuthenticationHeader(authApiURLConnection);

        return authApiURLConnection;
    }
    
    /**
     * Add the HTTP Basic authentication header which should be present on every API call.
     * 
     * @param restApiURLConnection The open connection to the REST API.
     */
    private static void addAuthenticationHeader(HttpURLConnection restApiURLConnection) {
        String userPwd = REST_API_USERNAME + ":" + REST_API_PASSWORD;
        String encoded = DatatypeConverter.printBase64Binary(userPwd.getBytes());
        restApiURLConnection.setRequestProperty("Authorization", "Basic " + encoded);
    }
    
    /**
     * Query the Auth API to determine the correct Service Provider address where the user's account is hosted. It will
     * be null if the account is hosted on the Identity Provider server itself.
     * 
     * @return the Service Provider address for the user's account, formatted as a URL (e.g.
     *         "http://ibm.blueworkslive/com"); may be null
     */
    public static String getServiceProviderAddress() throws IOException, JSONException {

        // Authenticate with the server.
        StringBuilder authUrlBuilder = new StringBuilder(REST_API_SERVER);
        authUrlBuilder.append(REST_API_CALL_AUTH);
        authUrlBuilder.append("?version=").append(REST_API_AUTH_VERSION);

        if (REST_API_ACCOUNT_NAME != null) {
            authUrlBuilder.append("&account=").append(REST_API_ACCOUNT_NAME);
        }

        // Note that the call to the Auth API uses the "GET" method.
        HttpURLConnection restApiURLConnection = getRestApiConnection(authUrlBuilder.toString(), "GET");
        if (restApiURLConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            System.err.println("Error calling the Blueworks Live REST API: " + restApiURLConnection.getResponseMessage());
            return null;
        }

        // Process the results of the Auth API.
        InputStream restApiStream = restApiURLConnection.getInputStream();
        try {
            JSONObject authenticateResult = new JSONObject(restApiStream);
            String userStatus = (String) authenticateResult.get("result");
            if (!userStatus.equals("authenticated")) {
                System.err.println("Error: User has incorrect status=" + userStatus);
                return null;
            }
            String serviceProviderAddress;
            try {
                serviceProviderAddress = (String) authenticateResult.get("serviceProviderAddress"); 
                //System.out.println("Service Provider address is: " + serviceProviderAddress);
            } catch (JSONException e) {
                // This simply means the Service Provider address property was not in the results, so we return null.
                serviceProviderAddress = null;
            }
            return serviceProviderAddress;
        } finally {
            // Clean up any streams we have opened.
            restApiStream.close();
        }
    }
}



