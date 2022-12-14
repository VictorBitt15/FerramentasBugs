package supplychain.activiti.rest.service.api;

import org.activiti.engine.ActivitiException;
import org.activiti.engine.ActivitiIllegalArgumentException;
import org.activiti.engine.ActivitiObjectNotFoundException;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.impl.persistence.entity.VariableInstanceEntity;
import org.activiti.engine.runtime.Execution;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.rest.exception.ActivitiContentNotSupportedException;
import org.activiti.rest.service.api.RestResponseFactory;
import org.activiti.rest.service.api.engine.variable.RestVariable;
import org.activiti.rest.service.api.engine.variable.RestVariable.RestVariableScope;
import org.activiti.rest.service.api.runtime.process.BaseExecutionVariableResource;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;

@Component
public class CustomBaseExcutionVariableResource extends BaseExecutionVariableResource {
    @Autowired
    protected Environment envi;

    @Autowired
    protected RestResponseFactory restResponseFactoryC;

    @Autowired
    protected RuntimeService runtimeServiceC;

    protected boolean isSerializableVariableAllowedC;

    @PostConstruct
    protected void postConstruct() {
        isSerializableVariableAllowedC = envi.getProperty("rest.variables.allow.serializable", Boolean.class, true);
    }

    protected byte[] getVariableDataByteArray(Execution execution, String variableName, String scope,
                                              HttpServletResponse response) {

        try {
            byte[] result = null;

            RestVariable variable = getVariableFromRequest(execution, variableName, scope, true);
            if (RestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE.equals(variable.getType())) {
                result = (byte[]) variable.getValue();
                response.setContentType("application/octet-stream");

            } else if (RestResponseFactory.SERIALIZABLE_VARIABLE_TYPE.equals(variable.getType())) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                ObjectOutputStream outputStream = new ObjectOutputStream(buffer);
                outputStream.writeObject(variable.getValue());
                outputStream.close();
                result = buffer.toByteArray();
                response.setContentType("application/x-java-serialized-object");

            } else {
                throw new ActivitiObjectNotFoundException("The variable does not have a binary data stream.", null);
            }
            return result;

        } catch (IOException ioe) {
            throw new ActivitiException("Error getting variable " + variableName, ioe);
        }
    }

    protected RestVariable setBinaryVariable(MultipartHttpServletRequest request, Execution execution, int
            responseVariableType, boolean isNew) {

        // Validate input and set defaults
        if (request.getFileMap().size() == 0) {
            throw new ActivitiIllegalArgumentException("No file content was found in request body.");
        }

        // Get first file in the map, ignore possible other files
        MultipartFile file = request.getFile(request.getFileMap().keySet().iterator().next());

        if (file == null) {
            throw new ActivitiIllegalArgumentException("No file content was found in request body.");
        }

        String variableScope = null;
        String variableName = null;
        String variableType = null;

        Map<String, String[]> paramMap = request.getParameterMap();
        for (String parameterName : paramMap.keySet()) {

            if (paramMap.get(parameterName).length > 0) {

                if (parameterName.equalsIgnoreCase("scope")) {
                    variableScope = paramMap.get(parameterName)[0];

                } else if (parameterName.equalsIgnoreCase("name")) {
                    variableName = paramMap.get(parameterName)[0];

                } else if (parameterName.equalsIgnoreCase("type")) {
                    variableType = paramMap.get(parameterName)[0];
                }
            }
        }

        try {

            // Validate input and set defaults
            if (variableName == null) {
                throw new ActivitiIllegalArgumentException("No variable name was found in request body.");
            }

            if (variableType != null) {
                if (!RestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE.equals(variableType) && !RestResponseFactory
                        .SERIALIZABLE_VARIABLE_TYPE.equals(variableType)) {
                    throw new ActivitiIllegalArgumentException("Only 'binary' and 'serializable' are supported as variable type" +
                            ".");
                }
            } else {
                variableType = RestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE;
            }

            RestVariableScope scope = RestVariableScope.LOCAL;
            if (variableScope != null) {
                scope = RestVariable.getScopeFromString(variableScope);
            }

            if (variableType.equals(RestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE)) {
                // Use raw bytes as variable value
                byte[] variableBytes = IOUtils.toByteArray(file.getInputStream());
                setVariable(execution, variableName, variableBytes, scope, isNew);

            } else if (isSerializableVariableAllowedC) {
                // Try deserializing the object
                ObjectInputStream stream = new ObjectInputStream(file.getInputStream());
                Object value = stream.readObject();
                setVariable(execution, variableName, value, scope, isNew);
                stream.close();
            } else {
                throw new ActivitiContentNotSupportedException("Serialized objects are not allowed");
            }

            if (responseVariableType == RestResponseFactory.VARIABLE_PROCESS) {
                return restResponseFactoryC.createBinaryRestVariable(variableName, scope, variableType, null, null, execution
                        .getId());
            } else {
                return restResponseFactoryC.createBinaryRestVariable(variableName, scope, variableType, null, execution.getId(),
                        null);
            }

        } catch (IOException ioe) {
            throw new ActivitiIllegalArgumentException("Could not process multipart content", ioe);
        } catch (ClassNotFoundException ioe) {
            throw new ActivitiContentNotSupportedException("The provided body contains a serialized object for which the class " +
                    "is nog found: " + ioe.getMessage());
        }

    }

    public RestVariable setSimpleVariable(RestVariable restVariable, Execution execution, boolean isNew) {
        if (restVariable.getName() == null) {
            throw new ActivitiIllegalArgumentException("Variable name is required");
        }

        // Figure out scope, revert to local is omitted
        RestVariableScope scope = restVariable.getVariableScope();
        if (scope == null) {
            scope = RestVariableScope.LOCAL;
        }

        Object actualVariableValue = restResponseFactoryC.getVariableValue(restVariable);
        setVariable(execution, restVariable.getName(), actualVariableValue, scope, isNew);

        return constructRestVariable(restVariable.getName(), actualVariableValue, scope, execution.getId(), false);
    }

    protected void setVariable(Execution execution, String name, Object value, RestVariableScope scope, boolean isNew) {
        // Create can only be done on new variables. Existing variables should
        // be updated using PUT
        boolean hasVariable = hasVariableOnScope(execution, name, scope);
        if (isNew && hasVariable) {
            throw new ActivitiException("Variable '" + name + "' is already present on execution '" + execution.getId() + "'.");
        }

        if (!isNew && !hasVariable) {
            throw new ActivitiObjectNotFoundException("Execution '" + execution.getId() + "' doesn't have a variable with name:" +
                    " '" + name + "'.", null);
        }

        if (scope == RestVariableScope.LOCAL) {
            runtimeServiceC.setVariableLocal(execution.getId(), name, value);
        } else {
            if (execution.getParentId() != null) {
                runtimeServiceC.setVariable(execution.getParentId(), name, value);
            } else {
                runtimeServiceC.setVariable(execution.getId(), name, value);
            }
        }
    }

    protected boolean hasVariableOnScope(Execution execution, String variableName, RestVariableScope scope) {
        boolean variableFound = false;

        if (scope == RestVariableScope.GLOBAL) {
            if (execution.getParentId() != null && runtimeServiceC.hasVariable(execution.getParentId(), variableName)) {
                variableFound = true;
            }

        } else if (scope == RestVariableScope.LOCAL) {
            if (runtimeServiceC.hasVariableLocal(execution.getId(), variableName)) {
                variableFound = true;
            }
        }
        return variableFound;
    }

    public RestVariable getVariableFromRequest(Execution execution, String variableName, String scope, boolean includeBinary) {

        boolean variableFound = false;
        Object value = null;

        if (execution == null) {
            throw new ActivitiObjectNotFoundException("Could not find an execution", Execution.class);
        }

        RestVariableScope variableScope = RestVariable.getScopeFromString(scope);
        if (variableScope == null) {
            // First, check local variables (which have precedence when no scope
            // is supplied)
            if (runtimeServiceC.hasVariableLocal(execution.getId(), variableName)) {
                value = runtimeServiceC.getVariableLocal(execution.getId(), variableName);
                variableScope = RestVariableScope.LOCAL;
                variableFound = true;
            } else {
                if (execution.getParentId() != null) {
                    value = runtimeServiceC.getVariable(execution.getParentId(), variableName);
                    variableScope = RestVariableScope.GLOBAL;
                    variableFound = true;
                }
            }
        } else if (variableScope == RestVariableScope.GLOBAL) {
            // Use parent to get variables
            if (execution.getParentId() != null) {
                value = runtimeServiceC.getVariable(execution.getParentId(), variableName);
                variableScope = RestVariableScope.GLOBAL;
                variableFound = true;
            }
        } else if (variableScope == RestVariableScope.LOCAL) {

            value = runtimeServiceC.getVariableLocal(execution.getId(), variableName);
            variableScope = RestVariableScope.LOCAL;
            variableFound = true;
        }

        if (!variableFound) {
            throw new ActivitiObjectNotFoundException("Execution '" + execution.getId() + "' doesn't have a variable with name:" +
                    " '" + variableName + "'.", VariableInstanceEntity.class);
        } else {
            return constructRestVariable(variableName, value, variableScope, execution.getId(), includeBinary);
        }
    }

    protected RestVariable constructRestVariable(String variableName, Object value, RestVariableScope variableScope, String
            executionId, boolean includeBinary) {

        return restResponseFactoryC.createRestVariable(variableName, value, variableScope, executionId, RestResponseFactory
                .VARIABLE_EXECUTION, includeBinary);
    }

    /**
     * Get valid execution from request. Throws exception if execution doen't exist or if execution id is not provided.
     */
    protected Execution getExecutionFromRequest(String executionId) {
        Execution execution = runtimeServiceC.createExecutionQuery().executionId(executionId).singleResult();
        if (execution == null) {
            throw new ActivitiObjectNotFoundException("Could not find an execution with id '" + executionId + "'.", Execution.class);
        }
        return execution;
    }

    public Execution getProcessInstanceFromRequest(String processInstanceId) {
        Execution execution = runtimeServiceC.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (execution == null) {
            throw new ActivitiObjectNotFoundException("Could not find a process instance with id '" + processInstanceId + "'.", ProcessInstance.class);
        }
        return execution;
    }

    protected String getExecutionIdParameter() {
        return "executionId";
    }

    protected boolean allowProcessInstanceUrl() {
        return false;
    }
}
