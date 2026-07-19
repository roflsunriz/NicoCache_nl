package dareka.processor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URLConnection;
import java.util.Arrays;
// import java.lang.reflect.InvocationTargetException;
// import java.util.LinkedHashSet;
// import java.util.Objects;

import dareka.common.Logger;

/**
 * Place to put workaround codes. They might violate standard coding regulation.
 * All dirty codes should be placed here to avoid scattering everywhere.
 * Additionally, this class does not have public access.
 *
 */
class Workarounds {
    /**
     * dirty hack!!
     *
     * change internal variable to avoid broken char problem.
     * see {@link HttpHeader}
     */
    static void dirtyChangeHttpURLConnectionImplEncoding() {
        try {
            Field encoding =
                    dirtyGetAccessibleField("sun.net.NetworkClient", "encoding");
            String originalEncoding = (String) encoding.get(null);
            encoding.set(null, "ISO8859_1");
            Logger.debugWithThread("changed NetworkClient.encoding from "
                    + originalEncoding + " to " + encoding.get(null));
        } catch (Exception e) {
            Logger.warning("failed to set workaround for multi bytes chars: "
                    + e.getMessage());
            Logger.debugWithThread(e);
        }
    }

    /**
     * dirty hack!!
     *
     * Slow server response prevent quick stop because HttpURLConnection
     * wait the response and we can not abort it in the normal way.
     * This method closes socket forcedly to avoid waiting inside
     * HttpURLConnection.
     *
     * HttpURLConnection may still wait for name resolving in
     * {@link InetAddress}
     */
    static void dirtyCloseHttpURLConnectionImplSocket(URLConnection con) {
        try {
            if (con == null) {
                return;
            }
            if (!(con instanceof HttpURLConnection)) {
                return;
            }

            Field http =
                    dirtyGetAccessibleField(
                            "sun.net.www.protocol.http.HttpURLConnection",
                            "http");
            Object httpClient = http.get(con); // don't cast to avoid NoClassDefFoundError
            if (httpClient == null) {
                return;
            }

            Field serverSocket =
                    dirtyGetAccessibleField("sun.net.NetworkClient",
                            "serverSocket");
            Socket s = (Socket) serverSocket.get(httpClient);
            if (s == null) {
                return;
            }

            s.close();
            Logger.debugWithThread("forcely close socket for " + con.getURL());
        } catch (Exception e) {
            Logger.warning("failed to set workaround for waiting server response: "
                    + e.getMessage());
            Logger.debugWithThread(e);
        }
    }

    // // 2024-04-07: 実装変更のためコメントアウト. 新実装が安定していたら消す.
    // static void dirtyChangeHttpURLConnectionImplAllowedMethods() {
    //     // https://stackoverflow.com/questions/51378681/httpurlconnection-error-invalid-http-method-patch
    //     try {
    //         Field methods =
    //             dirtyGetAccessibleField("java.net.HttpURLConnection", "methods");
    //         dirtyRemoveFinal(methods);
    //
    //         String[] originalMethods = (String[]) methods.get(null);
    //
    //         LinkedHashSet<String> methodsSet = new LinkedHashSet<>(Arrays.asList(originalMethods));
    //         methodsSet.add("PATCH");
    //         String[] newMethods = methodsSet.toArray(new String[0]);
    //
    //         methods.set(null, newMethods);
    //
    //         Logger.debugWithThread(
    //             "changed HttpURLConnection.methods from "
    //             + Arrays.toString(originalMethods) + " to "
    //             + Arrays.toString((String[]) methods.get(null)));
    //     } catch (Exception e) {
    //         Logger.warning(
    //             "failed to set workaround for allowing patch method: "
    //             + e.getMessage());
    //         Logger.debugWithThread(e);
    //     }
    // }
    static void dirtyChangeHttpURLConnectionImplAllowedMethods() {
        if (Runtime.version().feature() >= 9) {
            Logger.debugWithThread(
                "skipped legacy HttpURLConnection PATCH workaround on Java "
                + Runtime.version().feature());
            return;
        };

        // https://stackoverflow.com/questions/56039341/get-declared-fields-of-java-lang-reflect-fields-in-jdk12/77705202#77705202

        try {
            Field methods = dirtyGetAccessibleField(
                "java.net.HttpURLConnection", "methods");

            dirtyRemoveFinal(methods);

            MethodHandle handle = dirtyGetInvokableMethodHandle(
                HttpURLConnection.class, methods);

            final String[] newMethods = {
                "GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE"
                , "PATCH"
            };

            handle.invoke(newMethods);

            Logger.debugWithThread("changed HttpURLConnection.methods to "
                                   + Arrays.toString(newMethods));
        } catch (Throwable e) {
            Logger.warning(
                "failed to set workaround for allowing patch method: "
                + e.getMessage());
            Logger.debugWithThread(e);
        };
    };

    private static MethodHandle dirtyGetInvokableMethodHandle(
        Class<?> targetClass, Field field) throws Exception {

        // https://stackoverflow.com/questions/56039341/get-declared-fields-of-java-lang-reflect-fields-in-jdk12/77705202#77705202

        Class<?> memberNameClass = Class.forName("java.lang.invoke.MemberName");

        Constructor<?> memberNameConstructor =
            memberNameClass.getDeclaredConstructor(Field.class,
                                                   boolean.class);

        memberNameConstructor.setAccessible(true);

        Object memberNameInstanceForField =
            memberNameConstructor.newInstance(field, true);

        Field memberNameFlagsField =
            memberNameClass.getDeclaredField("flags");

        memberNameFlagsField.setAccessible(true);

        int origFlags =
            (int)memberNameFlagsField.getInt(memberNameInstanceForField);

        //Manipulate flags to remove hints to it being final
        memberNameFlagsField.setInt(memberNameInstanceForField,
                                    origFlags & ~Modifier.FINAL);

        Method getReferenceKindMethod =
            memberNameClass.getDeclaredMethod("getReferenceKind");

        getReferenceKindMethod.setAccessible(true);

        byte getReferenceKind =
            (byte)getReferenceKindMethod.invoke(memberNameInstanceForField);

        MethodHandles.Lookup mh =
            MethodHandles.privateLookupIn(targetClass,
                                          MethodHandles.lookup());

        Method getDirectFieldCommonMethod =
            mh.getClass().getDeclaredMethod("getDirectFieldCommon",
                                            byte.class, Class.class,
                                            memberNameClass, boolean.class);

        getDirectFieldCommonMethod.setAccessible(true);

        //Invoke last method to obtain the method handle

        return (MethodHandle)getDirectFieldCommonMethod.invoke(
            mh, getReferenceKind, field.getDeclaringClass(),
            memberNameInstanceForField, false);
    };

    private static Field dirtyGetAccessibleField(String className,
            String fieldName) throws ClassNotFoundException,
            NoSuchFieldException {
        Class<?> c = Class.forName(className);
        Field field = c.getDeclaredField(fieldName);
        field.setAccessible(true);

        return field;
    }

    private static void dirtyRemoveFinal(Field field) throws Exception {

        // https://stackoverflow.com/questions/56039341/get-declared-fields-of-java-lang-reflect-fields-in-jdk12/77705202#77705202

        Method[] classMethods = Class.class.getDeclaredMethods();

        Method declaredFieldMethod = null;

        // Method declaredFieldMethod = Arrays.stream(classMethods).filter(x -> Objects.equals(x.getName(), "getDeclaredFields0")).findAny().orElseThrow();

        for (Method x : classMethods) {
            if ("getDeclaredFields0".equals(x.getName())) {
                declaredFieldMethod = x;
            };
        };

        declaredFieldMethod.setAccessible(true);

        Field[] declaredFieldsOfField =
            (Field[]) declaredFieldMethod.invoke(Field.class, false);

        // Arrays.stream(declaredFieldsOfField).filter(x -> Objects.equals(x.getName(), "modifiers")).findAny().orElseThrow();

        Field modifiersField = null;
        for (Field x : declaredFieldsOfField) {
            if ("modifiers".equals(x.getName())) {
                modifiersField = x;
            };
        };

        modifiersField.setAccessible(true);

        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
    };

    // private static void dirtyRemoveFinal(Field field)
    //     throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException, InvocationTargetException {
    //     // https://stackoverflow.com/questions/56039341/get-declared-fields-of-java-lang-reflect-fields-in-jdk12
    //     Method getDeclaredFields0 = Class.class.getDeclaredMethod("getDeclaredFields0", boolean.class);
    //     getDeclaredFields0.setAccessible(true);
    //     Field[] fields = (Field[]) getDeclaredFields0.invoke(Field.class, false);
    //     Field modifiersField = null;
    //     for (Field each : fields) {
    //         if ("modifiers".equals(each.getName())) {
    //             modifiersField = each;
    //             break;
    //         }
    //     }
    //     if (modifiersField == null)
    //         throw new NoSuchFieldException("modifiers");

    //     modifiersField.setAccessible(true);
    //     modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
    // }

}
