# Soyken Play — Sunucu Taraflı Abonelik Doğrulama & RTDN Kurulum Rehberi

Bu dizin, **Soyken Play** (`com.aistudio.soykenplay.txvqen`) için Google Play Billing Library aboneliklerinin **sıfır güven (zero-trust)** mimarisiyle sunucu tarafında doğrulanmasını sağlayan **Firebase Cloud Functions (Node.js 20, Gen 2)** kodlarını içerir.

> **Güvenlik Kuralı:** Google Play Servis Hesabı JSON anahtarı **kesinlikle Android uygulamasına veya git deposuna eklenmez**. Yalnızca Google Cloud Secret Manager (`PLAY_SERVICE_ACCOUNT_JSON`) içinde saklanır.

---

## 1. Google Play Console Abonelik Tanımlaması

Google Play Console -> **Para kazanma -> Ürünler -> Abonelikler** bölümünde aşağıdaki kimliklerle aboneliği oluşturun:

- **Uygulama Paket Adı:** `com.aistudio.soykenplay.txvqen`
- **Subscription ID (Ürün Kimliği):** `soyken_premium`
- **Temel Plan 1 (Base Plan ID):** `monthly` (Aylık yenilenen)
- **Temel Plan 2 (Base Plan ID):** `yearly` (Yıllık yenilenen)

---

## 2. Google Play Developer API Servis Hesabı Yetkilendirmesi

1. Google Cloud Console (`gen-lang-client-0649889808` projesi) -> **APIs & Services -> Enable APIs**:
   - **Google Play Android Developer API** (`androidpublisher.googleapis.com`) etkinleştirin.
   - **Cloud Pub/Sub API** (`pubsub.googleapis.com`) etkinleştirin.
2. **IAM & Admin -> Service Accounts** üzerinden bir servis hesabı oluşturun (ör. `soyken-play-billing@gen-lang-client-0649889808.iam.gserviceaccount.com`) ve JSON anahtar dosyasını indirin.
3. **Google Play Console -> Kullanıcılar ve izinler -> Yeni kullanıcı davet et** bölümüne servis hesabı e-postasını ekleyin ve şu izinleri verin:
   - *Finansal verileri, siparişleri ve iptal anketi yanıtlarını görüntüleme*
   - *Siparişleri ve abonelikleri yönetme*

---

## 3. Cloud Secret Manager & RTDN Pub/Sub Kurulumu

Aşağıdaki komutları proje kök dizininde çalıştırın:

```bash
# 1. Bağımlılıkları yükleyin
cd functions && npm install && cd ..

# 2. Servis hesabı JSON anahtarını yalnızca sunucu tarafında Secret Manager'a kaydedin
firebase functions:secrets:set PLAY_SERVICE_ACCOUNT_JSON --project gen-lang-client-0649889808 < /path/to/play-service-account.json

# 3. RTDN için Pub/Sub konusunu (topic) oluşturun ve Google Play'e yayınlama yetkisi verin
gcloud pubsub topics create play-rtdn-soyken-play --project=gen-lang-client-0649889808
gcloud pubsub topics add-iam-policy-binding play-rtdn-soyken-play \
  --project=gen-lang-client-0649889808 \
  --member="serviceAccount:google-play-developer-notifications@system.gserviceaccount.com" \
  --role="roles/pubsub.publisher"

# 4. Cloud Functions ve Firestore kurallarını yayınlayın
firebase deploy --only functions,firestore:rules --project gen-lang-client-0649889808
```

---

## 4. Google Play Console RTDN Bağlantısı

1. Google Play Console -> **Para kazanma kurulumu (Monetization setup)** -> **Gerçek zamanlı geliştirici bildirimleri (RTDN)**.
2. Konu adı (Topic name) alanına şunu girin:
   `projects/gen-lang-client-0649889808/topics/play-rtdn-soyken-play`
3. **Test bildirimi gönder** butonuna tıklayarak `playRtdnWebhook` fonksiyonunun çalıştığını Firebase Logs üzerinden doğrulayın.

---

## 5. Firestore Veritabanı Yapısı

- **Veritabanı ID:** `ai-studio-android-soykenpl-53c6cd02-d945-4e02-aa9f-5926a37a6c5f`
- `/users/{uid}/entitlements/premium` -> Kullanıcı yalnızca kendi belgesini **okuyabilir**; yazma/silme yalnızca Cloud Functions Admin SDK ile yapılır.
- `/play_purchase_tokens/{tokenHash}` -> İstemci erişimine tamamen kapalıdır; RTDN olaylarında `purchaseToken -> uid` eşleşmesi için kullanılır.
