// Copyright 2010 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.ldap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.enterprise.connector.ldap.LdapConstants.AuthType;
import com.google.enterprise.connector.ldap.LdapConstants.ConfigName;
import com.google.enterprise.connector.ldap.LdapConstants.ErrorMessages;
import com.google.enterprise.connector.ldap.LdapConstants.LdapConnectionError;
import com.google.enterprise.connector.ldap.LdapConstants.Method;
import com.google.enterprise.connector.ldap.LdapHandler.LdapConnectionSettings;
import com.google.enterprise.connector.ldap.LdapHandler.LdapRule;
import com.google.enterprise.connector.ldap.LdapSchemaFinder.SchemaResult;
import com.google.enterprise.connector.spi.ConfigureResponse;
import com.google.enterprise.connector.spi.ConnectorFactory;
import com.google.enterprise.connector.spi.ConnectorType;
import com.google.enterprise.connector.util.connectortype.ConnectorFields.AbstractField;
import com.google.enterprise.connector.util.connectortype.ConnectorFields.EnumField;
import com.google.enterprise.connector.util.connectortype.ConnectorFields.IntField;
import com.google.enterprise.connector.util.connectortype.ConnectorFields.MultiCheckboxField;
import com.google.enterprise.connector.util.connectortype.ConnectorFields.SingleLineField;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.NamingException;

/**
 * {@link ConnectorType} implementation for the Ldap Connector. Implemented
 * using the {@link ConnectorFields} class.
 */
public class LdapConnectorType implements ConnectorType {

  private static final Logger LOG = Logger.getLogger(LdapConnectorType.class.getName());

  private final LdapHandlerI ldapHandler;

  public LdapConnectorType(LdapHandlerI ldapHandler) {
    this.ldapHandler = ldapHandler;
  }

  public static final String RESOURCE_BUNDLE_NAME =
      "com/google/enterprise/connector/ldap/config/" +
      "LdapConnectorResources";

  public static String makeDisplayPassword(String password) {
    String displayPassword;
    if (password == null) {
      displayPassword = "null";
    } else if (password.length() < 1) {
      displayPassword = "<empty>";
    } else {
      displayPassword = "####";
    }
    return displayPassword;
  }

  /**
   * Holder object for managing the private state for a single configuration
   * request.
   */
  private class FormManager {

    /**
     * The maximum results examined to construct a schema
     */
    private static final int MAX_SCHEMA_RESULTS = 1000;

    private final ImmutableList<AbstractField> fields;

    private final SingleLineField hostField, userField, passwordField, baseDnField,
        filterField;
    private final IntField portField;
    private final EnumField<AuthType> authTypeField;
    private final EnumField<Method> methodField;
    private final MultiCheckboxField schemaField;

    private final ResourceBundle bundle;
    private final Map<String, String> config;

    ConfigureResponse configureResponse = null;

    private static final String SCHEMA_INSTRUCTIONS = "schema_instructions";
    private static final String 
        SCHEMA_APPENDER_FUNCTION_NAME = "appendToSchema";
    private static final String 
        GET_INDEXOF_FUNCTION_NAME = "getIndexOf";
    private static final String 
        ONCLICK_FUNCTION_CALL = SCHEMA_APPENDER_FUNCTION_NAME + "(this)";
    // HTML code allowing insertion of javascript
    private static final String 
        SCRIPT_START = "<script type=\"text/javascript\">//<![CDATA[\n";
    private static final String SCRIPT_END = "//]]></script>\n";

    /** Sets field values from config that came from HTML form. */
    FormManager(Map<String, String> configMap, ResourceBundle bundle) {
      this.config = Maps.newHashMap(configMap);
      hostField =
          new SingleLineField(ConfigName.HOSTNAME.toString(), true, false);
      portField = new IntField(ConfigName.PORT.toString(), false, 389);
      authTypeField =
          new EnumField<AuthType>(ConfigName.AUTHTYPE.toString(), true,
          AuthType.class,
          AuthType.ANONYMOUS);
      userField =
          new SingleLineField(ConfigName.USERNAME.toString(), false, false);
      passwordField =
          new SingleLineField(ConfigName.PASSWORD.toString(), false, true);
      methodField =
          new EnumField<Method>(ConfigName.METHOD.toString(), true,
          Method.class, Method.STANDARD);
      baseDnField =
          new SingleLineField(ConfigName.BASEDN.toString(), false, false);
      filterField =
          new SingleLineField(ConfigName.FILTER.toString(), true, false);
      schemaField =
          new MultiCheckboxField(ConfigName.SCHEMA.toString(), false, null,
          SCHEMA_INSTRUCTIONS, new MultiCheckboxField.Callback() {
              @Override public Map<String, String> getAttributes(String key) {
                Map<String, String> attributes = Maps.newHashMap();
                attributes.put("onclick", ONCLICK_FUNCTION_CALL);
                if (key.equalsIgnoreCase(LdapHandler.DN_ATTRIBUTE)) {
                  attributes.put("disabled", "disabled");
                }
                return attributes;
              }
            });

      fields = ImmutableList.<AbstractField> of(
          hostField,
          portField,
          authTypeField,
          userField,
          passwordField,
          methodField,
          baseDnField,
          filterField,
          schemaField);
      // TODO(Max): remove traces of the schemaKey field that used to exist
      // delaying this for now because it's close to release and I don't want
      // to destabilize the code-base

      for (AbstractField field: fields) {
        field.setBoldLabel(false);
      }

      this.bundle = bundle;

      LdapConnectorConfig ldapConnectorConfig = new LdapConnectorConfig(config);

      String hostname = ldapConnectorConfig.getHostname();
      LOG.fine("hostname " + hostname);
      hostField.setValueFromString(hostname);

      int port = ldapConnectorConfig.getPort();
      LOG.fine("port " + port);
      portField.setValueFromInt(port);

      AuthType authtype = ldapConnectorConfig.getAuthtype();
      LOG.fine("authtype " + authtype);
      authTypeField.setValue(authtype);

      String username = ldapConnectorConfig.getUsername();
      LOG.fine("username " + username);
      userField.setValueFromString(username);

      String password = ldapConnectorConfig.getPassword();
      String displayPassword = makeDisplayPassword(password);
      LOG.fine("password " + displayPassword);
      passwordField.setValueFromString(password);

      Method method = ldapConnectorConfig.getMethod();
      LOG.fine("method " + method);
      methodField.setValue(method);

      String basedn = ldapConnectorConfig.getBasedn();
      LOG.fine("basedn " + basedn);
      baseDnField.setValueFromString(basedn);

      String filter = ldapConnectorConfig.getFilter();
      LOG.fine("filter " + filter);
      filterField.setValueFromString(filter);

      Set<String> selectedAttributes = ldapConnectorConfig.getSchema();

      if (LOG.isLoggable(Level.INFO)) {
        String string = dumpKeysToString(selectedAttributes);
        LOG.info("FormManager selectedAttributes size:" + selectedAttributes.size()
            + " contents:" + string);
      }
      schemaField.setSelectedKeys(selectedAttributes);

      LdapConnectionSettings settings = ldapConnectorConfig.getSettings();

      // Note: we ignore connection errors here, because we just want to
      // set up the state in the way it was when it was saved
      try {
        ldapHandler.setLdapConnectionSettings(settings);
      } catch (Throwable t) {
        // FIXME These errors are getting lost if not caught here. They need to
        // be logged for debugging purposes and also need to allow the
        // configuration to proceed even if there is any sort of connection
        // error. May be revisited/removed once the internal logic is fool
        // proof.
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        LOG
            .info("Error while trying set the connection in FormManager constructor using settings :"
                + settings + sw.toString());
      }

      LdapRule rule = ldapConnectorConfig.getRule();
      if (rule != null) {
        try {
          getSchema(rule);
        } catch (IllegalStateException e) {
          reportError(e);
        }
      }
    }

    private void reportError(IllegalStateException e) {
      Throwable cause = e.getCause();
      if (cause == null) {
        String errorName = e.getMessage();
        ErrorMessages errorMessage = ErrorMessages.safeValueOf(errorName);
        if (errorMessage != null) {
          // we know this error
          LOG.log(Level.WARNING, "Error getting schema for existing connector: "
              + errorMessage.toString());
          return;
        }
      } else {
        // cause != null
        if (cause instanceof NamingException) {
          // There was some kind of jndi problem - expected here
          LOG.log(Level.WARNING, "Exception thrown getting schema for existing connector: " +
              cause.getClass().getSimpleName());
          // log stack trace for debugging
          LOG.log(Level.FINE, "Stack trace:", e);
        } else if (cause instanceof IOException) {
          LOG.log(Level.WARNING, "Exception thrown getting schema for existing connector: " +
              cause.getClass().getSimpleName());
          // log stack trace for debugging
          LOG.log(Level.FINE, "Stack trace:", e);
        }
      }
      // fallback
      LOG.log(Level.WARNING, "Unexpected Exception thrown getting schema for existing connector:",
          e);
    }

    String getFormRows(Collection<String> errorKeys) {
      StringBuilder buf = new StringBuilder();
      // Insert the "preview" label
      buf.append("<tr><td><br/><b>");
      buf.append(bundle.getString(LdapConstants.LDAP_CONNECTOR_CONFIG));
      String format = bundle.getString(LdapConstants.PREVIEW_HTML);
      Object[] arguments = new Object[] {bundle.getString(LdapConstants.PREVIEW_TAG)};
      String message = MessageFormat.format(format, arguments);
      buf.append(message);
      buf.append("</b></td></tr>\n");
      // Note: Immutable lists are guaranteed to return the fields in the order
      // they were specified, so this order is deterministic
      for (AbstractField field : fields) {
        String name = field.getName();
        boolean highlightError = (errorKeys == null) ? false : errorKeys.contains(name);
        buf.append(field.getSnippet(bundle, highlightError));
        buf.append("\n");
      }
      // schemavalue hidden variable is used to get all the selected attributes
      // from the UI as one json string.
      String schemaValue = LdapConnectorConfig.
          getSchemaValueFromConfig(config);

      // hidden field for 'configured'
      buf.append("<tr style='display: none'><td>\n");
      buf.append("<input type = 'hidden' id = '");
      buf.append(ConfigName.CONFIGURED).append("'");
      buf.append(" name = '").append(ConfigName.CONFIGURED);
      buf.append("' value = '");
      buf.append(isConfigured());
      buf.append("' />");
      buf.append("</td></tr>\n");

      // hidden field for 'schemavalue'
      buf.append("<tr style='display: none'><td>\n");
      buf.append("<input type = 'hidden' id = 'schemavalue'");
      buf.append(" name = 'schemavalue' value = '").append(schemaValue);
      buf.append("' />");
      buf.append(SCRIPT_START);
      buf.append(getIndexOfJavaScript());
      buf.append(getSchemaAppenderJavaScript());
      buf.append(SCRIPT_END);
      buf.append("</td></tr>\n");
      return buf.toString();
    }

    private void validateNotNull(Object o, String name) {
      // convenience routine for checking and reporting on things that "should never happen"
      if (o != null) {
        return;
      }
      LOG.warning(ErrorMessages.UNKNOWN_CONNECTION_ERROR.name() + " null " + name);
      configureResponse =
          new ConfigureResponse(bundle.getString(ErrorMessages.MISSING_FIELDS.name()),
          getFormRows(null));
    }

    private void getSchema(LdapRule rule) {
      ldapHandler.setQueryParameters(rule, null, LdapHandler.DN_ATTRIBUTE, MAX_SCHEMA_RESULTS);

      LdapSchemaFinder schemaFinder = new LdapSchemaFinder(ldapHandler);
      validateNotNull(schemaFinder, "schemaFinder");
      if (configureResponse != null) {
        return;
      }

      SchemaResult schemaResult = schemaFinder.find(MAX_SCHEMA_RESULTS);
      validateNotNull(schemaResult, "schemaResult");
      //check if schema result fields are returned, display error of there are none
      if (schemaResult.getResultCount() <= 0) {
        configureResponse =
            new ConfigureResponse(bundle.getString(ErrorMessages.NO_RESULTS_FOR_GIVEN_SEARCH_STRING
                .name()), getFormRows(null));
      }
      
      if (configureResponse != null) {
        return;
      }

      Set<String> foundSchema = schemaResult.getSchema().keySet();
      validateNotNull(foundSchema, "foundSchema");
      if (configureResponse != null) {
        return;
      }
      schemaField.setKeys(foundSchema);
    }

    ConfigureResponse validateConfig(ConnectorFactory factory) {

      configureResponse = null;

      Collection<String> errorKeys = assureAllMandatoryFieldsPresent();
      if (errorKeys.size() != 0) {
        return new ConfigureResponse(bundle.getString(ErrorMessages.MISSING_FIELDS.name()),
            getFormRows(errorKeys));
      }
      LOG.fine("Required fields validated");

      // special validation for authentication type "simple"
      LOG.fine("Validating fields for Simple Authentication");
      String authtypeString = config.get(ConfigName.AUTHTYPE.toString()).trim();
      if (authtypeString.equals(AuthType.SIMPLE.toString())) {
        String username = config.get(ConfigName.USERNAME.toString()).trim();
        String password = config.get(ConfigName.PASSWORD.toString()).trim();
        Collection<String> simpleAuthnFieldNameErrorKeys = new ArrayList<String>();
        LOG.fine("Validating Simple Authentication username ["+username+"] and password");
        // validate each field - username and password are required
        if (username.length() == 0) {
          simpleAuthnFieldNameErrorKeys.add(ConfigName.USERNAME.toString());
          LOG.info("Username is empty");
        }
        if (password.length() == 0) {
          simpleAuthnFieldNameErrorKeys.add(ConfigName.PASSWORD.toString());
          LOG.info("Password is empty");
        }

        // return errors
        if (simpleAuthnFieldNameErrorKeys.size() != 0) {
          LOG.info("Simple Authentication validation failed");
          return new ConfigureResponse(bundle.getString(ErrorMessages.MISSING_FIELDS.name()),
              getFormRows(simpleAuthnFieldNameErrorKeys));
        }
      }
      LOG.fine("Simple Authentication validation successful!");
      
      LdapConnectorConfig ldapConnectorConfig = new LdapConnectorConfig(config);
      LdapConnectionSettings settings = ldapConnectorConfig.getSettings();
      ldapHandler.setLdapConnectionSettings(settings);

      // report any connection errors
      Map<LdapConnectionError, Throwable> errors = ldapHandler.getErrors();
      if (errors.size() > 0) {
        String errorMessage = "";
        for (LdapConnectionError e : errors.keySet()) {
          errorMessage += bundle.getString(e.name());
        }
        return new ConfigureResponse(errorMessage, getFormRows(errorKeys));
      }

      ConfigureResponse failed = null;

      // TODO: check for empty schema found
      LdapRule rule = ldapConnectorConfig.getRule();

      getSchema(rule);
      // the above call sets the configureResponse non-null if there was an error
      // and sets puts the schema found in the schemaField
      if (configureResponse != null) {
        // report the error
        return configureResponse;
      }

      // look at the config again, using the new schema
      ldapConnectorConfig = new LdapConnectorConfig(config);
      schemaField.setSelectedKeys(ldapConnectorConfig.getSchema());

      if (!schemaField.hasValue()) {
        if (!schemaField.isEmpty()) {
          HashSet<String> selectDnKey = new HashSet<String>();
          selectDnKey.add(LdapHandler.DN_ATTRIBUTE);
          schemaField.setSelectedKeys(selectDnKey);
        }
        return new ConfigureResponse(null, getFormRows(null));
      }

      if (!isConfigured()) {
        config.put(ConfigName.CONFIGURED.toString(), "true");
        return new ConfigureResponse(null, getFormRows(null), config);
      }

      ensureConfigIsComplete(config);

      String errorMessageHtml;

      // If we have been given a factory, try to instantiate a connector.
      try {
        if (factory != null) {
          factory.makeConnector(config);
        }
        LOG.info("Successfully instantiated Ldap Connector");
        HashMap<String, String> configData = Maps.newHashMap(config);
        return new ConfigureResponse(null, null, configData);
      } catch (Exception e) {
        // We should perform sufficient validation so instantiation succeeds.
        LOG.log(Level.SEVERE, "failed to instantiate Ldap Connector ", e);
        return new ConfigureResponse(bundle.getString(ErrorMessages.CONNECTOR_INSTANTIATION_FAILED
            .name()), getFormRows(null));
      }
    }

    /**
     * Returns true if the connector has been configured
     */
    private boolean isConfigured() {
      return Boolean.parseBoolean(config.get(ConfigName.CONFIGURED.toString()));
    }

    /**
     * Checks to make sure all required fields are set.
     *
     * @return a collection of missing field names.
     */
    private Collection<String> assureAllMandatoryFieldsPresent() {
      List<String> missing = new ArrayList<String>();
      for (AbstractField field : fields) {
        if (field.isMandatory() && (!field.hasValue())) {
          missing.add(field.getName());
        }
      }
      return missing;
    }
    
    /**
     * This method generates a String representation of a javascript function
     * that gets called on 'onclick' event of all the checkboxes representing
     * the LDAP server attributs. 
     * Following javascript gets generated by this method.
     * 
     * <script type="text/javascript">
     * var schemaList = new Array();
     * function appendToSchema(chkbox) {
     *   if (chkbox.checked) {
     *     schemaList.push(chkbox.value);
     *   } else {
     *     schemaList.splice(schemaList.indexOf(chkbox.value),1);
     *   }
     *   document.getElementById('schemavalue').value  = JSON.stringify(schemaList);
     * }
     * </script> 
     *
     * @return String representation of a javascript function.
     */
    private String getSchemaAppenderJavaScript() {
      StringBuilder buf = new StringBuilder();
      buf.append("var schemaList = new Array();")
      .append("function ")
      .append(SCHEMA_APPENDER_FUNCTION_NAME).append("(chkbox) {")
      .append("if (schemaList.length == 0) {")
      .append("schemaList = JSON.parse(document.getElementById('schemavalue').value);")
      .append("}")
      .append("if (chkbox.checked) {")
      .append("schemaList.push(chkbox.value);")
      .append("document.getElementById('schemavalue').value")
      .append(" = JSON.stringify(schemaList);")
      .append("}")
      .append("else {")
      .append("if(" + GET_INDEXOF_FUNCTION_NAME + "(schemaList,chkbox.value) >= 0) {")
      .append("schemaList.splice(" + GET_INDEXOF_FUNCTION_NAME + "(schemaList,chkbox.value),1);")
      .append("document.getElementById('schemavalue').value")
      .append(" = JSON.stringify(schemaList);")
      .append("}}}");
      return buf.toString();
    }
    
    /**
     * This method generates a String representation of a JavaScript function getIndexOf.
     * The java script function returns the index of the value in array object. If the value
     * is not found in array -1 is returned.
     * This method is added as workaround to HtmlUnit failing on JS indexOf function.
     * Following java script gets generated by this method.
     * 
     * function getIndexOf(arr, value) { 
     *   for(var i=0; i< arr.length; i++){
     *    if(arr[i] == value) 
     *      return i; 
     *   } 
     *   return -1; 
     *  }
     * 
     * @return String representation of a JavaScript function.
     */
    private String getIndexOfJavaScript() {

      StringBuilder buf = new StringBuilder();
      buf.append("function ")
      .append(GET_INDEXOF_FUNCTION_NAME)
      .append("(arr, value) {")
      .append("for(var i=0; i< arr.length; i++){")
      .append("if(arr[i] == value)")
      .append("return i;")
      .append("}")
      .append("return -1;")
      .append("}");
      return buf.toString();

    }
  }

  @VisibleForTesting
  public ResourceBundle getResourceBundle(Locale locale) {
    ResourceBundle resourceBundle = ResourceBundle.getBundle(RESOURCE_BUNDLE_NAME, locale);
    return resourceBundle;
  }

  @Override
  public ConfigureResponse getConfigForm(Locale locale) {
    ConfigureResponse configureResponse;
    ResourceBundle resourceBundle = getResourceBundle(locale);
    HashMap<String, String> configMap = Maps.newHashMap();
    FormManager formManager = new FormManager(configMap, resourceBundle);
    configureResponse = new ConfigureResponse("", formManager.getFormRows(null));
    if (LOG.isLoggable(Level.FINE)) {
      LOG.fine("getConfigForm form:\n" + configureResponse.getFormSnippet());
    }
    return configureResponse;
  }

  @Override
  public ConfigureResponse getPopulatedConfigForm(Map<String, String> config, Locale locale) {
    if (LOG.isLoggable(Level.FINE)) {
      String string = dumpConfigToString(config);
      LOG.fine("getPopulatedConfigForm config:" + string);
    }
    FormManager formManager = new FormManager(config, getResourceBundle(locale));
    ConfigureResponse res = new ConfigureResponse("", formManager.getFormRows(null));
    if (LOG.isLoggable(Level.FINE)) {
      LOG.fine("getPopulatedConfigForm form:\n" + res.getFormSnippet());
    }
    return res;
  }

  @Override
  public ConfigureResponse validateConfig(Map<String, String> config, Locale locale,
      ConnectorFactory factory) {
    if (LOG.isLoggable(Level.FINE)) {
      String string = dumpConfigToString(config);
      LOG.fine("validateConfig config:" + string);
    }
    
    LOG.fine("Creating FormManager for validating LDAP connector config");
    FormManager formManager = null;
    try {
      formManager = new FormManager(config, getResourceBundle(locale));
    } catch (Throwable t) {
      // FIXME These errors are getting lost if not caught here. They need to
      // be logged for debugging purposes and also need to allow the
      // configuration to proceed even if there is any sort of connection
      // error. May be revisited/removed once the internal logic is fool
      // proof.
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      t.printStackTrace(pw);
      LOG.severe("Error in FormManager Constructor " + t.toString() + sw.toString());
    }
    LOG.fine("Calling validate for FormManager");
    ConfigureResponse res = null;
    try {
      res = formManager.validateConfig(factory);
    } catch (Throwable t) {
      // FIXME These errors are getting lost if not caught here. They need to
      // be logged for debugging purposes and also need to allow the
      // configuration to proceed even if there is any sort of connection
      // error. May be revisited/removed once the internal logic is fool
      // proof.
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      t.printStackTrace(pw);
      LOG.severe("Error in Validate config " + t.toString() + sw.toString());
    }
    LOG.fine("FormManager validate successful");
    
    if (res == null) {
      LOG.info("validateConfig form: success - returning null");
    } else if (res.getMessage() == null && res.getFormSnippet() == null) {
      LOG.info("validateConfig form: success - returning new config");
      if (LOG.isLoggable(Level.FINE)) {
        String string = dumpConfigToString(res.getConfigData());
        LOG.fine("validateConfig outconfig:" + string);
      }
    } else {
      LOG.info("validateConfig returning message: " + res.getMessage());
      if (LOG.isLoggable(Level.FINE)) {
        LOG.fine("validateConfig returning message: " + res.getMessage());
        LOG.fine("validateConfig returning new form:\n" + res.getFormSnippet());
      }
    }
    return res;
  }

  /**
   * For logging
   */
  private static String dumpConfigToString(Map<String, String> config) {
    StringBuilder sb = new StringBuilder();
    for (String key : config.keySet()) {
      sb.append("\n");
      sb.append("Key ");
      sb.append(key);
      sb.append(" Value \"");
      String value = config.get(key);
      if ("password".equals(key)) {
        value = makeDisplayPassword(value);
      }
      sb.append(value);
      sb.append("\"");
    }
    String string = sb.toString();
    return string;
  }

  /**
   * For logging
   */
  private static String dumpKeysToString(Set<String> keys) {
    StringBuilder sb = new StringBuilder();
    for (String key : keys) {
      sb.append("\n");
      sb.append("Key ");
      sb.append(key);
    }
    String string = sb.toString();
    return string;
  }

  /**
   * In order to avoid problems during instantiation, a stored config should
   * contain a clue for each element referenced in the connectorInstance.xml.
   * This makes sure that the config map is complete in that sense, by adding
   * default values for any missing keys.
   */
  private static void ensureConfigIsComplete(Map<String, String> config) {
    // set known defaults
    setDefaultIfNecessary(config, ConfigName.METHOD.toString(),
        Method.getDefault().toString());
    setDefaultIfNecessary(config, ConfigName.AUTHTYPE.toString(),
        AuthType.getDefault().toString());
    setDefaultIfNecessary(config, ConfigName.PORT.toString(),
        Integer.toString(LdapConstants.DEFAULT_PORT));
    for (ConfigName cn : ConfigName.values()) {
      // Schema and SchemaKey are treated specially below
      if (ConfigName.SCHEMA == cn
          || ConfigName.SCHEMA_KEY == cn) {
        continue;
      }
      setDefaultIfNecessary(config, cn.toString(), " ");
    }
    setDefaultIfNecessary(config, ConfigName.SCHEMA_KEY.toString(),
        LdapHandler.DN_ATTRIBUTE);
  }

  private static void setDefaultIfNecessary(Map<String, String> config, String key,
      String defaultValue) {
    if (config.containsKey(key)) {
      return;
    }
    config.put(key, defaultValue);
  }
}
