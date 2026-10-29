# Omni Android

**Omni** — کلاینت واقعی VPN برای اندروید با برند Omni؛ نه WebView و نه شبیه‌ساز اتصال.

این مخزن فورک اختصاصیِ [`patterniha/PattNG`](https://github.com/patterniha/PattNG) است که خود بر پایهٔ v2rayNG ساخته شده است. تاریخچه و لایسنس upstream حفظ شده‌اند.

## موتور و قابلیت‌های واقعی
- `VpnService` بومی و اتصال تمام‌صفحه/پروکسی محلی.
- Xray-core از `AndroidLibXrayLite` (VLESS، VMess، Trojan، Shadowsocks، Hysteria2، WireGuard و …).
- هستهٔ **Aether** برای WARP/MASQUE/ECH و قابلیت‌های ضد-DPI.
- Psiphon و pluggable transport به‌عنوان لایه‌های پشتیبانِ خود upstream.
- تست پینگ واقعی، زنجیرهٔ پروکسی، Policy Group و Fallback، Per-app proxy، Routing و Backup.
- پشتیبانی از لینک مستقیم `vless://`، `vmess://`، `trojan://`، `ss://`، `aether://` و غیره از تلگرام/مرورگر.

## تغییرات Omni در شاخهٔ `omni`
- نام، `applicationId = net.omnigate.app`، آیکون و پالت رنگ اختصاصی Omni.
- پنل Omni به‌صورت یک مقصد اختیاری در منو؛ موتور VPN به پنل یا WebView وابسته نیست.
- ورودی Deep-link برای کانفیگ‌ها و `omni://install-config?url=…`.
- به‌روزرسانی‌یاب به Releaseهای همین مخزن وصل شده است.
- ساخت CI با کلید امضای اختصاصی در GitHub Secrets؛ کلید خصوصی در سورس نیست.

> **وضعیت صادقانه:** APK این مخزن موتور اتصال واقعی دارد، اما اتصال موفق به سرورهای شما به معتبر بودن کانفیگ و در دسترس بودن زیرساخت بستگی دارد. خطای 403 صفحهٔ Worker نیز یک مسدودی سمت Cloudflare است و با تعویض رابط اپ حل نمی‌شود.

## ساخت
روش پیشنهادی: GitHub Actions → **Omni APK** روی شاخهٔ `omni`. زنجیرهٔ NDK/Rust/Go، Xray AAR، Aether، Psiphon و hev-socks5-tunnel را همان workflow می‌سازد و APKهای امضاشده را artifact می‌کند.

## منابع و لایسنس
- [PattNG](https://github.com/patterniha/PattNG) — پایهٔ این فورک.
- [v2rayNG](https://github.com/2dust/v2rayNG) — پروژهٔ مادر.
- [Xray-core](https://github.com/XTLS/Xray-core) و [AndroidLibXrayLite](https://github.com/patterniha/AndroidLibXrayLite).
- [Aether](https://github.com/patterniha/Aether)، [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

لایسنس GPL-3.0 و اطلاعات copyright فایل `LICENSE` حفظ شده است. سورس تغییرات Omni در همین مخزن عمومی است.
