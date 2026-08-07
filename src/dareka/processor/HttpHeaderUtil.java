package dareka.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;

/**
 * Http Headerに関するNicocache側の処理をまとめる
 */
public class HttpHeaderUtil {
    private HttpHeaderUtil() {
        // avoid instantiation
    }

    private static final Pattern spaceTabPattern = Pattern.compile(
        " |\t");
    private static String removeSpaceTab(String s) {
        if (s == null) {
            return null;
        };
        Matcher m = spaceTabPattern.matcher(s);
        return m.replaceAll("");
    };

    /**
     * Nicocacheが処理出来るEncodingリスト.
     *
     * このEncodingとはContent-EncodingやAccept-Encodingのそれ.
     * "identity"も含まれることに注意.
     * 小文字のみ.
     */
    private static final String[] SUPPORTED_ENCODINGS = new String[] {
        "identity", "gzip", "deflate", "br", "zstd"
    };

    /**
     * Nicocacheが処理出来る Encoding であるかどうか.
     *
     * この Encoding とは Content-Encoding や Accept-Encoding のそれ.
     */
    static public boolean isSupportedEncoding(String encoding) {
        if (encoding == null) {
            return false;
        };
        String normalized = encoding.trim();
        for (String code : SUPPORTED_ENCODINGS) {
            if (code.equalsIgnoreCase(normalized)) {
                return true;
            };
        };
        return false;
    };

    /**
     * HttpヘッダであるAccept-Encodingの値表現からNicocacheが対応していない
     * Encodingを削除します.
     *
     * nullを渡した場合はnullが返ります.
     * 対応Encodingのゼロウェイト指定はそのまま残ります.
     * 非ゼロウェイトの"*"は対応Encodingに置き換えられます.
     * 上記以外の未対応Encodingと空Encodingは削除されます.
     * 上記処理後にEncodingが空になった場合は"identity"が返ります.
     */
    static public String adjustAcceptEncoding(String acceptEncodings) {
// OWS              = *( SP / HTAB )
// weight           = OWS ";" OWS "q=" qvalue
// qvalue           = ( "0" [ "." 0*3DIGIT ] )
//                  / ( "1" [ "." 0*3("0") ] )
// codings          = content-coding / "identity" / "*"
// Accept-Encoding  = #( codings [ weight ] )
//                  => codings [ weight ] *(OWS "," OWS codings [ weight ] )

        if (acceptEncodings == null) {
            return null;
        };

        String[] codeWeightList = acceptEncodings.split(",");
        List<String> dest = new ArrayList<String>();

        for (String codeWeightPack : codeWeightList) {
            String[] codeWeightAfter = codeWeightPack.split(";", 3);
            String codeOWS = codeWeightAfter[0];
            String weightOWS = "";
            String afterOWS = "";

            if (codeWeightAfter.length >= 2) {
                weightOWS = ";" + codeWeightAfter[1];
            };

            if (codeWeightAfter.length >= 3) {
                afterOWS = ";" + codeWeightAfter[2];
            };

            // encodingはcase-insensitive.
            String code = removeSpaceTab(codeOWS).toLowerCase(Locale.ROOT);
            // weightはcase-sensitive.
            String weight = removeSpaceTab(weightOWS);

            boolean zeroWeight = weight.equals(";q=0")
                || weight.equals(";q=0.0")
                || weight.equals(";q=0.00")
                || weight.equals(";q=0.000")
                // これは不正だが一応チェック.
                || weight.equals(";q=0.");

            if (isSupportedEncoding(code)) {
                dest.add(codeOWS + weightOWS + afterOWS);
                continue;
            };

            if (code.equals("*")) {
                if (zeroWeight) {
                    continue;
                }
                // 非0ウェイトの * はNicocacheが対応している encoding に展開する.
                for (String scode : SUPPORTED_ENCODINGS) {
                    if (!scode.equals("identity")) {
                        dest.add(scode + weightOWS + afterOWS);
                    }
                };
                continue;
            };

            // - code は非対応の encoding である.
            // - あるいは空白である.
            // - do nothing.
        };

        if (dest.isEmpty()) {
            return "identity";
        };

        return String.join(",", dest);
    };

    /**
     * requestHeaderのAccept-EncodingからNicocacheが扱えないEncodingを
     * 削除します.
     *
     * Accept-Encodingヘッダが存在しない場合は何もしません.
     * 細かい仕様は adjustAcceptEncoding(String) を参照.
     */
    public static void adjustAcceptEncoding(HttpRequestHeader requestHeader) {
        String acceptEncoding = requestHeader.getMessageHeader(HttpHeader.ACCEPT_ENCODING);
        if (acceptEncoding == null) {
            return;
        };
        requestHeader.setMessageHeader(
            HttpHeader.ACCEPT_ENCODING,
            adjustAcceptEncoding(acceptEncoding));
    };
}
