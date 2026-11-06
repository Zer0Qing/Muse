import { useI18n } from "../i18n/I18nProvider.js";
import { hostBuildDetails, hostBuildStatus, readHostBuild } from "./hostBuildGuard.js";

interface HostVersionBannerProps {
  /** 注入信息的来源；测试传一个普通对象，不依赖真实 window。 */
  readonly source?: unknown;
}

/**
 * S6：App 与网页资源协议不一致时的提示条。
 *
 * 只在**确实不匹配**时渲染：协议一致或读不到注入时一个节点都不生成。
 * 这条纪律和提示本身同样重要 —— 空白的转圈页面让人焦虑，
 * 而无事的黄色横幅让人开始无视所有横幅。
 */
export function HostVersionBanner({ source = typeof window === "undefined" ? undefined : window }: HostVersionBannerProps) {
  const { t } = useI18n();
  const info = readHostBuild(source);
  if (hostBuildStatus(info) !== "protocol-mismatch") return null;
  const details = hostBuildDetails(info);

  return (
    <div className="host-version-banner" role="status">
      <p className="host-version-title">
        {t("host.version.mismatch", { hostProtocol: details.hostProtocol, clientProtocol: details.clientProtocol })}
      </p>
      <p className="host-version-detail">{t("host.version.appLabel", { version: details.appVersion })}</p>
    </div>
  );
}
