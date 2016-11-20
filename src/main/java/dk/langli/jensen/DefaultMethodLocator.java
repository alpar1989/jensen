package dk.langli.jensen;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DefaultMethodLocator implements MethodLocator {
	private final Logger log = LoggerFactory.getLogger(DefaultMethodLocator.class);
	private final ObjectMapper mapper;

	public DefaultMethodLocator(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public static String getMethodName(Request request) {
		return request.getMethod().substring(request.getMethod().lastIndexOf('.') + 1);
	}

	public static String getClassName(Request request) {
		return request.getMethod().substring(0, request.getMethod().lastIndexOf('.'));
	}

	@Override
	public MethodCall getInvocation(Request request) throws ClassNotFoundException, MethodNotFoundException {
		MethodCall methodCall = null;
		String methodName = getMethodName(request);
		String className = getClassName(request);
		List<Object> requestParams = request.getParams();
		Class<?> clazz = Class.forName(className);
		methodCall = getMethodCall(clazz, methodName, requestParams);
		if(methodCall == null) {
			throw new MethodNotFoundException(String.format("Method %s in class %s not found", methodName, className), null);
		}
		return methodCall;
	}

	private MethodCall getMethodCall(Class<?> clazz, String methodName, List<Object> requestParams) throws MethodNotFoundException {
		MethodCall methodCall = null;
		Method[] methods = clazz.getMethods();
		int methodIndex = 0;
		Map<String, Object> incompatibleMethods = new HashMap<>();
		while(methodCall == null && methodIndex < methods.length) {
			Method method = methods[methodIndex++];
			String signature = method.getName() + "(" + toString(method.getParameterTypes()) + ")";
			if(method.getName().equals(methodName)) {
				if(Modifier.isPublic(method.getModifiers())) {
					log.trace("Check method parameter compatibility: " + method.getName() + "(" + toString(method.getParameterTypes()) + ")");
					try {
						List<Object> params = deserializeParameterList(requestParams, method.getParameterTypes());
						if(params.size() == requestParams.size()) {
							log.trace(signature + " is compatible with the parameter list");
							methodCall = new MethodCall(method, params);
						}
					}
					catch(ParameterTypeException e) {
						Map<String, Object> parameterInfo = new HashMap<>();
						parameterInfo.put("parameterType", e.getParameterType());
						parameterInfo.put("index", e.getIndex());
						incompatibleMethods.put(signature, parameterInfo);
					}
				}
			}
		}
		if(methodCall == null) {
			String message = String.format("No method %s in class %s can take the given parameters", methodName, clazz.getSimpleName());
			Map<String, Object> incompatible = null;
			if(incompatibleMethods.size() > 0) {
				incompatible = new HashMap<>();
				incompatible.put("incompatible", incompatibleMethods);
			}
			throw new MethodNotFoundException(message, incompatible);
		}
		return methodCall;
	}

	private String toString(Class<?>[] parameterTypes) {
		String parmTypes = "";
		for(int i = 0; parameterTypes != null && i < parameterTypes.length; i++) {
			Class<?> type = parameterTypes[i];
			parmTypes += (i != 0 ? ", " : "") + type.getSimpleName();
		}
		return parmTypes;
	}

	private List<Object> deserializeParameterList(List<Object> params, Class<?>[] parameterTypes) throws ParameterTypeException {
		List<Object> deserializedparams = new ArrayList<Object>();
		if(params.size() == parameterTypes.length) {
			for(int i = 0; i < parameterTypes.length; i++) {
				try {
					Object o = mapper.convertValue(params.get(i), parameterTypes[i]);
					deserializedparams.add(o);
				}
				catch(IllegalArgumentException e) {
					String message = String.format("Parameter[%s] is not a %s", i, parameterTypes[i].getSimpleName());
					throw new ParameterTypeException(message, parameterTypes[i], i);
				}
			}
		}
		return deserializedparams;
	}
}
