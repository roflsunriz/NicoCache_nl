package dareka.common;

/**
 * [nl] Configの更新通知を受け取るためのインターフェース
 * @since NicoCache_nl+110219mod
 */
public interface ConfigObserver {

    public void update(Config config);

}
