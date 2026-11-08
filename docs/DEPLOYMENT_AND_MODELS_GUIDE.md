# Smarter AI — Apinex Bond API Architecture & Deployment Guide

## 1. Architecture Overview

Smarter AI uses a secure 3-tier architecture for conversational AI, authentication, conversation history sync, and media generation:

```
Android App (Kotlin / Jetpack Compose)
   │  1. Authenticates via Supabase Auth (/auth/v1) & Brevo 6-Digit Email OTP (/functions/v1/auth-otp)
   │  2. Syncs conversations & messages via Supabase PostgREST (/rest/v1) with Row Level Security (RLS)
   │  3. Streams Chat Completions via Supabase Edge Function (/functions/v1/ai-chat)
   │        └──► Apinex Bond API (https://api.apinex.bond/v1/chat/completions)
   │  4. Generates Images & Audio via Pollinations / FLUX / On-Device TTS (Media Studios only)
```

---

## 2. Apinex Bond API Configuration & Integrated Models

- **Base URL**: `https://api.apinex.bond/v1`
- **Chat Completions Endpoint**: `POST https://api.apinex.bond/v1/chat/completions`
- **Models Discovery Endpoint**: `GET https://api.apinex.bond/v1/models`
- **Authentication**: Server-side `Authorization: Bearer <APINEX_API_KEY>` (never exposed in the Android APK or client `BuildConfig`)

### Integrated Chat Models (Exact Order & IDs)

| # | Display Name | Model ID | Context Window |
|---|---|---|---|
| 1 | Muse Spark 1.3 | `free/muse-spark-1.3` | 1,000,000 tokens |
| 2 | Gemini 3.8 Flash | `free/gemini-3.8-flash` | 1,000,000 tokens |
| 3 | Qwen 3.8 MAX | `free/qwen-3.8-max` | 1,000,000 tokens |
| 4 | Kimi K3 | `free/kimi-k3` | 1,000,000 tokens |
| 5 | Deepseek V4.1 Flash 1017 | `free/deepseek-v4.1-flash` | 1,000,000 tokens |
| 6 | GLM 5.3 | `free/glm-5.3` | 1,000,000 tokens |
| 7 | GLM-5.3 Flash *(Default)* | `free/glm-5.3-flash` | 1,000,000 tokens |

---

## 3. Supabase Edge Function Secrets & Deployment

Configure your `APINEX_API_KEY` and Brevo OTP secrets in Supabase and deploy the `ai-chat` and `auth-otp` Edge Functions:

```bash
supabase secrets set \
  APINEX_API_KEY="your-apinex-bond-api-key" \
  APINEX_BASE_URL="https://api.apinex.bond/v1" \
  BREVO_API_KEY="your-brevo-transactional-email-api-key" \
  BREVO_SENDER_EMAIL="auth@smarter-keys-daily.com" \
  BREVO_SENDER_NAME="Smarter AI"

supabase db push
supabase functions deploy ai-chat --no-verify-jwt
supabase functions deploy auth-otp --no-verify-jwt
```
