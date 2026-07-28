package dareka.processor.impl;

import dareka.common.Logger;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// - キャッシュファイル名に設定する情報と、キャッシュファイル名から得られる情報
//   を管理する(前者は本当か？).
public class VideoDescriptor implements Comparable<VideoDescriptor> {

    private final String id;       // smXXXXXXX
    private final String type;     // sm
    private final String idNumber; // XXXXXXX
    private final String postfix;  // .mp4

    // キャッシュファイル名がsmXXX[vm,(vb,)ab]_title.ext形式であるかどうか.
    // dmcとdomandで以降では必ずtrue.
    private final boolean dmc;

    // - ここに"for classic"とコメントがあったが誤りでは？
    // - smXXXlow のようにlowが付く場合にtrue. 複数の品質があるdmcやdomand仕様で
    //   も動画(と音声)が最高品質以外の場合にこの印は付いている.
    private final boolean low;     // economy

    // for dmc and dms(domand)
    // - キャッシュが(ファイルではなく)ディレクトリならtrue.
    // - hlsという名前だったのを変更した.
    private final boolean dir;
    private final String videoMode; // 品質モード
    private final int height;
    // - 一時期生成されていた"360p_low"のようにビデオモードに"_low"が含まれる場合にtrue.
    // - これはeconomyやlow(最高品質であるかどうか)とは別モノ(とはいえ"360p_low"
    //   が最高品質であるパターンは存在しない).
    // - たぶんdmcLowが1種類であることを前提にこのフラグを設置したと思う. しかし
    //   いつからか[なし,mid,low,lowest]が存在する. フラグでは管理出来ない.
    // - そのため比較が期待通りではない例があるはず.
    private final boolean dmcLow;

    private final int videoBitrate; // 単位は何？ 不明, 未指定で0.
    private final int audioBitrate; // 単位はkbps. 不明, 未指定で0.
    private final String shortenSrcId; // only for tmp file. これは何？

    // - ファイル名のmodeでpを付け忘れている場合を考慮してpが無い場合も処理対象
    //   にしておく.
    // - "_low"はdmc/hlsに付く. "-lowest"はdms(domand)に付く.
    // - "-mid"はdms(domand)に付く場合がある. 現時点ではこれが最高画質である場合
    //   しか確認できていない. そうではない動画が存在した場合は、品質比較処理を
    //   拡張しなければならない.
    private static final Pattern VIDEO_MODE_PATTERN = Pattern.compile("(\\d++)p?(_low|-lowest|-mid)?");

    private VideoDescriptor(String id, String postfix, boolean dmc, boolean low,
            String videoMode, int videoBitrate, int audioBitrate, String srcId) {
        this.id = id;
        this.type = id.substring(0, 2);
        this.idNumber = id.substring(2);
        this.postfix = postfix;
        this.dmc = dmc;
        this.low = low;
        if (dmc) {
            this.dir = Cache.HLS.equals(postfix);
            this.videoMode = videoMode;
            Matcher m = VIDEO_MODE_PATTERN.matcher(videoMode);
            if (m.matches()) {
                this.height = Integer.parseInt(m.group(1));
                if (m.group(2) == null) {
                    this.dmcLow = false;
                }
                else if (m.group(2).equals("_low") || m.group(2).equals("-lowest")) {
                    this.dmcLow = true;
                }
                else if (m.group(2).equals("-mid")) {
                    this.dmcLow = false;
                }
                else {
                    this.dmcLow = false;
                    Logger.info("unknown sub video mode: " + m.group(2));
                };
            } else {
                this.height = 0;
                this.dmcLow = false;
            }
            this.videoBitrate = videoBitrate;
            this.audioBitrate = audioBitrate;
            this.shortenSrcId = srcId.substring(0, Math.min(srcId.length(), 8));
        } else {
            this.dir = false;
            this.videoMode = null;
            this.height = 0;
            this.dmcLow = false;
            this.videoBitrate = 0;
            this.audioBitrate = 0;
            this.shortenSrcId = null;
        }
    }

    public static VideoDescriptor newClassic(String id, String postfix, boolean low) {
        return new VideoDescriptor(id, postfix, false, low, null, 0, 0, null);
    }

    public static VideoDescriptor newDmc(String id, String postfix, boolean low,
            String videoMode, int videoBitrate, int audioBitrate, String srcId) {
        return new VideoDescriptor(id, postfix, true, low, videoMode, videoBitrate, audioBitrate, srcId);
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getIdNumber() {
        return idNumber;
    }

    public String getPostfix() {
        return postfix;
    }

    // - キャッシュファイル名がdmc以降の仕様ならばtrue.
    // - dmc, dms(domand)でtrueであるはず.
    // - isDmcという既存名はExtension ABI互換のため維持する.
    public boolean isDmc() {
        return dmc;
    }

    public boolean isDir() { return dir; }

    public boolean isLow() {
        return low;
    }

    public String getVideoMode() {
        return videoMode;
    }

    public int getVideoBitrate() {
        return videoBitrate;
    }

    public int getAudioBitrate() {
        return audioBitrate;
    }

    public String getShortenSrcId() {
        return shortenSrcId;
    }

    public VideoDescriptor replaceId(String id) {
        return new VideoDescriptor(id, postfix, dmc, low, videoMode, videoBitrate, audioBitrate, shortenSrcId);
    }

    public VideoDescriptor replacePostfix(String postfix) {
        return new VideoDescriptor(id, postfix, dmc, low, videoMode, videoBitrate, audioBitrate, shortenSrcId);
    }

    public VideoDescriptor replaceLow(boolean low) {
        return new VideoDescriptor(id, postfix, dmc, low, videoMode, videoBitrate, audioBitrate, shortenSrcId);
    }

    public VideoDescriptor stripSrcId() {
        return new VideoDescriptor(id, postfix, dmc, low, videoMode, videoBitrate, audioBitrate, "");
    }

    private int postfixPriority = 0;

    private int getPostfixPriority() {
        if (postfixPriority != 0) {
            return postfixPriority;
        }
        if (postfix == null) {
            return 0;
        }
        if (dmc) {
            switch (postfix) {
            case Cache.HLS:
                postfixPriority = 10;
                break;
            case Cache.MP4:
                postfixPriority = 2;
                break;
            case Cache.FLV:
                postfixPriority = 1;
                break;
            default:
                postfixPriority = Integer.MIN_VALUE;
                break;
            }
        } else {
            postfixPriority = 0;
        }
        return postfixPriority;
    }

    /**
     * thisがotherより高い品質である場合にtrue(よって同品質の場合はfalse).
     *
     * dmcIsPreferred=true:
     *   0. dmc 比較はしない. thisがdmcではないならばreturn false.
     *   1. postfix 比較はしない.
     *        mp4かhlsを優先とした時(preferredPostfix=mp4|hls)に
     *        this.postfixが優先postfixでない(preferredPostfixと不等)場合にfalseを返す.
     *        this.postfix=swf|flvの場合はfalseしないが、dmc時代にswfとflvは存在しない.
     *        原則的にthis.postfixがpreferredPostfixと違う場合はfalse.
     *        preferredPostfixがnullの場合は何もしない.
     *   1.5. other is nullならばtrue.
     *   2. height
     *   3. dmcLow
     *   4. videoBitrate
     *   5. audioBitrate
     *   6. postfix
     * dmcIsPreferred=false(Classic):
     *  0. dmc 比較はしない. thisがdmcであるならばreturn false.
     *  0.5. other is nullならばtrue.
     *  1. economy
     *
     * @param other
     * @param dmcIsPreferred dmcである側を優先する時にtrue.
     * @param preferredPostfix 他の要素が同順位の時にpostfixがこれである側を優先とする.
     *   null時はデフォルトのpostfix優先順位によってthisとotherを比較する.
     *
     * 2025-03-24: 互換性を維持した変更をした.
     */
    protected boolean isPreferredThan
    (VideoDescriptor other, boolean dmcIsPreferred, String preferredPostfix) {
        return isPreferredThan(other, dmcIsPreferred, preferredPostfix, true, true);
    };
    /**
     * @param audioCheck falseなら音声品質に関する比較を行なわない.
     * @param videoCheck falseなら映像品質に関する比較を行なわない.
     */
    protected boolean isPreferredThan
    (VideoDescriptor other, boolean dmcIsPreferred, String preferredPostfix,
     boolean audioCheck, boolean videoCheck) {
        if (dmcIsPreferred) {
            if (!this.dmc) {
                return false;
            };
            // 1. postfix
            // - このnull checkは無くても挙動は変わらないが読みやすさのために置いておく.
            if (preferredPostfix != null) {
                if (!this.postfix.equals(preferredPostfix)) {
                    if (Cache.MP4.equals(preferredPostfix)) {
                        return false;
                    };
                    if (Cache.HLS.equals(preferredPostfix)) {
                        return false;
                    };
                };
            };
            // 1.5: other
            // - 2025-03: このnull checkは一番最初であるべきでは？
            //     しかし互換性のためにこのままにしておく.
            if (other == null) {
                return true;
            };
            if (videoCheck) {
                // 2. height
                if (this.height < other.height) {
                    return false;
                } else if (this.height > other.height) {
                    return true;
                };
                // 3. dmcLow
                if (this.dmcLow && !other.dmcLow) {
                    return false;
                } else if (!this.dmcLow && other.dmcLow) {
                    return true;
                }
                // 4. videoBitrate
                if (this.videoBitrate < other.videoBitrate) {
                    return false;
                } else if (this.videoBitrate > other.videoBitrate) {
                    return true;
                }
            };
            if (audioCheck) {
                // 5. audioBitrate
                if (this.audioBitrate < other.audioBitrate) {
                    return false;
                } else if (this.audioBitrate > other.audioBitrate) {
                    return true;
                }
            };
            // 6. postfix
            int thisPriority = this.getPostfixPriority();
            int otherPriority = other.getPostfixPriority();
            if (postfix != null) {
                if (this.postfix.equals(preferredPostfix)) {
                    thisPriority = Integer.MAX_VALUE;
                }
                if (other.postfix.equals(preferredPostfix)) {
                    otherPriority = Integer.MAX_VALUE;
                }
            }
            return thisPriority > otherPriority;
        } else {
            if (this.dmc) {
                return false;
            }
            if (other == null) {
                return true;
            }
            // 1. economy
            if (this.low && !other.low) {
                return false;
            } else if (!this.low && other.low) {
                return true;
            }
            return false;
        }
    }

    /**
     * 半順序上で，より良い動画データかを表す． thisがotherより明らかに良い，つまりthisがあればotherは不要な時にtrueを返す．
     *
     * dmcに対して，
     * - 解像度height
     * - dmcLow
     * - heightが同じ場合に映像ビットレート
     * - 音声ビットレート
     * - 拡張子 .hls > .mp4 > .flv
     * の全てが同じか優れていて，かつ，4点について同じではないときにtrue．
     * 安全のためheightが0とそうでないものの間には順序を付けない(false)．
     *
     * 非dmcに対しては，
     * - 非エコノミー動画(!low)が優先される
     * - lowが同じ場合，拡張子間に順序はつかずfalseを返す
     *
     * dmc動画と非dmc動画を比較した場合はfalseを返す．
     *
     * isPreferredThanとの違い:
     *   isPreferredThanのほうが全順序に近い． 例えば，flv要求時に300kbps
     *   mp4と600kbps flvがあれば 600kbps flvの方がpreferである．
     *   対して，300kbps mp4と600kbps flvの間にどちらがsuperiorかの順序は存在しない．
     */
    protected boolean isSuperiorThan(VideoDescriptor other) {
        if (other == null) {
            return true;
        }
        if (this.dmc != other.dmc) {
            return false;
        }
        if (this.dmc) {
            if (this.height != 0 && other.height == 0
                    || this.height == 0 && other.height != 0) {
                return false;
            }
            if (this.dmcLow && !other.dmcLow) {
                return false;
            } else if (!this.dmcLow && other.dmcLow) {
                return true;
            }
            if (this.height < other.height
                    || this.height == other.height && this.videoBitrate < other.videoBitrate
                    || this.audioBitrate < other.audioBitrate
                    || this.getPostfixPriority() < other.getPostfixPriority()) {
                return false;
            }
            if (this.height == other.height
                    && this.videoBitrate == other.videoBitrate
                    && this.audioBitrate == other.audioBitrate
                    && this.getPostfixPriority() == other.getPostfixPriority()) {
                return false;
            }
            return true;
        } else {
            if (this.low && !other.low) {
                return false;
            } else if (!this.low && other.low) {
                return true;
            }
            return false;
        }
    }

    boolean hasBitrate() {
        return this.videoBitrate != 0;
    }

    @Override
    public String toString() {
        if (isDmc()) {
            return getId() + (isLow() ? "low" : "")
                    + "[" + getVideoMode()
                    + (getVideoBitrate() == 0 ? "" : "," + getVideoBitrate())
                    + "," + getAudioBitrate() + "]"
                    + getShortenSrcId() + getPostfix();
        } else {
            return getId() + (isLow() ? "low" : "") + getPostfix();
        }
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 79 * hash + Objects.hashCode(this.id);
        hash = 79 * hash + (this.dmc ? 1 : 0);
        if (this.dmc) {
            hash = 79 * hash + Objects.hashCode(this.videoMode);
            hash = 79 * hash + this.videoBitrate;
            hash = 79 * hash + this.audioBitrate;
            hash = 79 * hash + Objects.hashCode(this.shortenSrcId);
            hash = 79 * hash + Objects.hashCode(this.postfix);
        } else {
            hash = 79 * hash + (this.low ? 1 : 0);
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final VideoDescriptor other = (VideoDescriptor) obj;
        if (this.dmc != other.dmc) {
            return false;
        }
        if (dmc) {
            if (this.videoBitrate != other.videoBitrate) {
                return false;
            }
            if (this.audioBitrate != other.audioBitrate) {
                return false;
            }
            if (!Objects.equals(this.shortenSrcId, other.shortenSrcId)) {
                return false;
            }
            if (!Objects.equals(this.videoMode, other.videoMode)) {
                return false;
            }
        } else {
            // dmcに対してはlowは同値性の判定に含めない
            if (this.low != other.low) {
                return false;
            }
        }
        if (!Objects.equals(this.id, other.id)) {
            return false;
        }
        // 非dmcに対してはpostfixは同値性の判定に含めない
        if (this.dmc && !Objects.equals(this.postfix, other.postfix)) {
            return false;
        }
        return true;
    }

    @Override
    public int compareTo(VideoDescriptor o) {
        int t;
        int n1 = Integer.parseInt(this.getIdNumber());
        int n2 = Integer.parseInt(o.getIdNumber());
        if (n1 < n2) {
            return -1;
        } else if (n1 > n2) {
            return 1;
        }

        // dmc動画のほうが前(小さい)
        if (dmc && !o.dmc) {
            return -1;
        } else if (!dmc && o.dmc) {
            return 1;
        }

        if (dmc) {
            // ビットレートが大きい方が前(小さい)
            if (videoBitrate < o.videoBitrate) {
                return 1;
            } else if (videoBitrate > o.videoBitrate) {
                return -1;
            }

            // 自然順序付けすべきだけどビットレートが同じならvideoModeも同じはず
            t = videoMode.compareTo(o.videoMode);
            if (t != 0) return t;

            if (audioBitrate < o.audioBitrate) {
                return 1;
            } else if (audioBitrate > o.audioBitrate) {
                return -1;
            }

            t = shortenSrcId.compareTo(o.shortenSrcId);
            if (t != 0) return t;

            // postfixは優先度が高いものが先
            int tp = this.getPostfixPriority();
            int op = o.getPostfixPriority();
            if (tp > op) {
                return -1;
            } else if (tp < op) {
                return 1;
            }
            // もし同じpriorityを返した時にはnullを末尾とした辞書順
            if (postfix == null && o.postfix == null) return 0;
            if (postfix == null) return 1;
            if (o.postfix == null) return -1;
            t = postfix.compareTo(o.postfix);
            if (t != 0) return t;
        } else {
            // lowが後(大きい)
            if (!low && o.low) {
                return -1;
            } else if (low && !o.low) {
                return 1;
            }
        }

        return 0;
    }
}
