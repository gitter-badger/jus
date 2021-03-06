/*
 * Copyright (C) 2015 Apptik Project
 * Copyright (C) 2012 Square, Inc.
 * Copyright (C) 2007 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.apptik.comm.jus.retro;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;

import io.apptik.comm.jus.Request;
import io.apptik.comm.jus.Response;

final class Utils {

  /** Returns true if {@code annotations} contains an instance of {@code cls}. */
  static boolean isAnnotationPresent(Annotation[] annotations,
      Class<? extends Annotation> cls) {
    for (Annotation annotation : annotations) {
      if (cls.isInstance(annotation)) {
        return true;
      }
    }
    return false;
  }

  static <T> void validateServiceInterface(Class<T> service) {
    if (!service.isInterface()) {
      throw new IllegalArgumentException("API declarations must be interfaces.");
    }
    // Prevent API interfaces from extending other interfaces. This not only avoids a bug in
    // Android (http://b.android.com/58753) but it forces composition of API declarations which is
    // the recommended pattern.
    if (service.getInterfaces().length > 0) {
      throw new IllegalArgumentException("API interfaces must not extend other interfaces.");
    }
  }

  public static Type getParameterUpperBound(ParameterizedType type) {
    Type[] types = type.getActualTypeArguments();
    if (types.length != 1) {
      throw new IllegalArgumentException(
              "Expected one type argument but got: " + Arrays.toString(types));
    }
    Type paramType = types[0];
    if (paramType instanceof WildcardType) {
      return ((WildcardType) paramType).getUpperBounds()[0];
    }
    return paramType;
  }

  public static Type getSecondParameterUpperBound(ParameterizedType type) {
    Type[] types = type.getActualTypeArguments();
    if (types.length != 2) {
      throw new IllegalArgumentException(
              "Expected one type argument but got: " + Arrays.toString(types));
    }
    Type paramType = types[1];
    if (paramType instanceof WildcardType) {
      return ((WildcardType) paramType).getUpperBounds()[0];
    }
    return paramType;
  }

  // This method is copyright 2008 Google Inc. and is taken from Gson under the Apache 2.0 license.
  public static Class<?> getRawType(Type type) {
    if (type instanceof Class<?>) {
      // Type is a normal class.
      return (Class<?>) type;

    } else if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;

      // I'm not exactly sure why getRawType() returns Type instead of Class. Neal isn't either but
      // suspects some pathological case related to nested classes exists.
      Type rawType = parameterizedType.getRawType();
      if (!(rawType instanceof Class)) throw new IllegalArgumentException();
      return (Class<?>) rawType;

    } else if (type instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) type).getGenericComponentType();
      return Array.newInstance(getRawType(componentType), 0).getClass();

    } else if (type instanceof TypeVariable) {
      // We could use the variable's bounds, but that won't work if there are multiple. Having a raw
      // type that's more general than necessary is okay.
      return Object.class;

    } else if (type instanceof WildcardType) {
      return getRawType(((WildcardType) type).getUpperBounds()[0]);

    } else {
      String className = type == null ? "null" : type.getClass().getName();
      throw new IllegalArgumentException("Expected a Class, ParameterizedType, or "
          + "GenericArrayType, but <" + type + "> is of type " + className);
    }
  }

  static boolean checkIfRequestRawType(Type type) {
    return type instanceof ParameterizedType && ((ParameterizedType) type).getRawType() ==
            Request.class;
  }

  static RuntimeException methodError(Method method, String message, Object... args) {
    return methodError(null, method, message, args);
  }

  static RuntimeException methodError(Throwable cause, Method method, String message,
      Object... args) {
    message = String.format(message, args);
    IllegalArgumentException e = new IllegalArgumentException(message
        + "\n    for method "
        + method.getDeclaringClass().getSimpleName()
        + "."
        + method.getName());
    e.initCause(cause);
    return e;

  }

  static Type getRequestResponseType(Type returnType) {
    if (!(returnType instanceof ParameterizedType)) {
      throw new IllegalArgumentException(
          "Request return type must be parameterized as Request<Foo> or Request<? extends Foo>");
    }
    final Type responseType = getParameterUpperBound((ParameterizedType) returnType);

    // Ensure the Call response type is not Response, we automatically deliver the Response object.
    if (getRawType(responseType) == Response.class) {
      throw new IllegalArgumentException(
          "Request<T> cannot use Response as its generic parameter. "
              + "Specify the response body type only (e.g., Request<TweetResponse>).");
    }
    return responseType;
  }

  private Utils() {
    // No instances.
  }
}
