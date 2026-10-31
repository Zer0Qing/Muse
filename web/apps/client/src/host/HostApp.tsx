import { useMemo, useState } from "react";
import { hostPinLogin } from "../api.js";
import { I18nProvider, useI18n } from "../i18n/I18nProvider.js";
import { navigatorLanguages, resolveLocale } from "../i18n/locale.js";
import { HostChat } from "./HostChat.js";
import { clearHostAuthenticated, isHostAuthenticated, markHostAuthenticated, switchToSaas } from "./host-mode.js";

function HostLogin({ onLogin }: { readonly onLogin: () => void }) {
  const { t } = useI18n();
  const [pin, setPin] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit() {
    if (!/^\d{6}$/.test(pin)) {
      setError(t("login.pinInvalid"));
      return;
    }
    setBusy(true);
    setError("");
    try {
      await hostPinLogin(pin);
      onLogin();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("login.failed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="shell host-login-screen host-scope">
      <section className="panel auth-panel host-login">
        <p className="eyebrow">{t("login.eyebrow")}</p>
        <h1>{t("login.title")}</h1>
        <p className="lede">{t("login.lede")}</p>
        <input
          className="host-pin-input"
          aria-label="Host PIN"
          inputMode="numeric"
          autoComplete="one-time-code"
          pattern="\d*"
          maxLength={6}
          type="text"
          placeholder="000000"
          value={pin}
          onChange={(event) => setPin(event.target.value.replace(/\D/g, "").slice(0, 6))}
          onKeyDown={(event) => {
            if (event.key === "Enter") void submit();
          }}
        />
        <button type="button" disabled={busy || pin.length !== 6} onClick={() => void submit()}>
          {busy ? t("login.busy") : t("login.submit")}
        </button>
        {error && (
          <>
            <p className="error">{error}</p>
            <button className="quiet" type="button" onClick={switchToSaas}>
              {t("login.switchToSaas")}
            </button>
          </>
        )}
      </section>
    </main>
  );
}

/**
 * Host (LAN) shell: PIN gate, then the mobile chat surface.
 *
 * 登录页还没拿到 Host 设置，语言按浏览器语言解析（system 规则）；连上之后
 * HostChat 会按 settings.values.language 再套一层 Provider 接管整棵树。
 */
export function HostApp() {
  const [authenticated, setAuthenticated] = useState(isHostAuthenticated);
  const loginLocale = useMemo(() => resolveLocale("system", navigatorLanguages()), []);

  if (!authenticated) {
    return (
      <I18nProvider locale={loginLocale}>
        <HostLogin
          onLogin={() => {
            markHostAuthenticated();
            setAuthenticated(true);
          }}
        />
      </I18nProvider>
    );
  }
  return (
    <HostChat
      onLogout={() => {
        clearHostAuthenticated();
        setAuthenticated(false);
      }}
    />
  );
}
