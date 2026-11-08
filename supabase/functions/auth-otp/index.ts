// ============================================================================
// Smarter AI — Dedicated Brevo Email OTP Edge Function (supabase/functions/auth-otp/index.ts)
// ============================================================================
// Endpoints:
//   GET  /auth-otp/health      -> Reports Brevo & Supabase OTP configuration status
//   POST /auth-otp/send-otp    -> Generates 6-digit numeric OTP, stores salted SHA-256 hash
//                                 in public.email_verification_otps, and delivers branded
//                                 Smarter AI email via Brevo Transactional Email API
//   POST /auth-otp/verify-otp  -> Verifies 6-digit OTP in constant time, enforces 5-minute
//                                 expiry, 5 max attempts, and single-use consumption, marks
//                                 user email confirmed in Supabase Auth, and returns session
// ============================================================================

import { serve } from "https://deno.land/std@0.208.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.7";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

// Server-side Brevo secrets (configured via Supabase Secrets; NEVER hardcoded or exposed to Android client)
function getBrevoConfig() {
  const apiKey = (Deno.env.get("BREVO_API_KEY") ?? "").trim();
  const senderEmail = (Deno.env.get("BREVO_SENDER_EMAIL") ?? "").trim();
  const senderName = (Deno.env.get("BREVO_SENDER_NAME") ?? "").trim() || "Authentication";
  return { apiKey, senderEmail, senderName };
}

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

const OTP_EXPIRY_MS = 5 * 60 * 1000; // 5 minutes
const OTP_RESEND_COOLDOWN_MS = 45 * 1000; // 45 seconds
const OTP_MAX_ATTEMPTS = 5;

const otpRateLimitMap = new Map<string, { count: number; windowStart: number }>();
const OTP_RATE_WINDOW_MS = 15 * 60 * 1000; // 15 minutes
const MAX_OTP_SENDS_PER_WINDOW = 6;
const MAX_OTP_VERIFIES_PER_WINDOW = 15;

interface OtpRecord {
  id: string;
  email: string;
  user_id: string | null;
  otp_hash: string;
  salt: string;
  attempts: number;
  max_attempts: number;
  expires_at: string;
  resend_available_at: string;
  used_at: string | null;
  invalidated: boolean;
  created_at: string;
}

function checkOtpRateLimit(key: string, maxAllowed: number): boolean {
  const now = Date.now();
  const entry = otpRateLimitMap.get(key);
  if (!entry || now - entry.windowStart > OTP_RATE_WINDOW_MS) {
    otpRateLimitMap.set(key, { count: 1, windowStart: now });
    return true;
  }
  if (entry.count >= maxAllowed) {
    return false;
  }
  entry.count += 1;
  return true;
}

function generateSecureSixDigitOtp(): string {
  const array = new Uint32Array(1);
  const range = 900000;
  const maxUnbiased = Math.floor(0xFFFFFFFF / range) * range;
  let randomVal = 0;
  do {
    crypto.getRandomValues(array);
    randomVal = array[0];
  } while (randomVal >= maxUnbiased);
  return String(100000 + (randomVal % range));
}

function generateHexSalt(byteLength = 16): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

async function computeOtpHash(salt: string, email: string, otpCode: string): Promise<string> {
  const normalized = `${salt}:${email.trim().toLowerCase()}:${otpCode.trim()}`;
  const data = new TextEncoder().encode(normalized);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

function timingSafeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

function buildSmarterAiOtpEmailHtml(displayName: string, otpCode: string): string {
  const safeName = (displayName || "there").replace(/[<>&"']/g, "");
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Verify your Smarter AI email</title>
</head>
<body style="margin:0;padding:0;background-color:#0B0F19;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#F8FAFC;">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background-color:#0B0F19;padding:32px 16px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="max-width:480px;background-color:#131B2E;border:1px solid #1E293B;border-radius:16px;padding:36px 28px;">
          <tr>
            <td align="left" style="padding-bottom:20px;">
              <span style="display:inline-block;background:linear-gradient(135deg,#6366F1,#06B6D4);color:#FFFFFF;font-weight:700;font-size:14px;letter-spacing:0.6px;padding:6px 12px;border-radius:8px;">SMARTER AI</span>
            </td>
          </tr>
          <tr>
            <td align="left" style="font-size:22px;font-weight:700;color:#F8FAFC;padding-bottom:12px;">
              Verify your email address
            </td>
          </tr>
          <tr>
            <td align="left" style="font-size:15px;line-height:1.6;color:#CBD5E1;padding-bottom:24px;">
              Hi ${safeName},<br/><br/>
              Enter the 6-digit verification code below in the Smarter AI app to finish setting up your account:
            </td>
          </tr>
          <tr>
            <td align="center" style="padding-bottom:24px;">
              <div style="background-color:#0F172A;border:2px solid #6366F1;border-radius:12px;padding:18px 24px;font-size:32px;font-weight:800;letter-spacing:8px;color:#FFFFFF;font-family:'Courier New',Courier,monospace;">
                ${otpCode}
              </div>
            </td>
          </tr>
          <tr>
            <td align="left" style="font-size:13px;line-height:1.6;color:#94A3B8;padding-bottom:20px;">
              This verification code expires in <strong style="color:#E2E8F0;">5 minutes</strong> and can only be used once.<br/>
              For your security, never share this code with anyone.
            </td>
          </tr>
          <tr>
            <td align="left" style="border-top:1px solid #1E293B;padding-top:18px;font-size:12px;line-height:1.5;color:#64748B;">
              If you did not request a Smarter AI account, you can safely ignore this email.
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>`;
}

async function sendOtpEmailViaBrevo(
  recipientEmail: string,
  recipientName: string,
  otpCode: string
): Promise<{ ok: boolean; error?: string }> {
  const { apiKey, senderEmail, senderName } = getBrevoConfig();
  if (!apiKey || !senderEmail) {
    return {
      ok: false,
      error: "Email delivery service (BREVO_API_KEY / BREVO_SENDER_EMAIL) is not configured on the server.",
    };
  }

  const htmlContent = buildSmarterAiOtpEmailHtml(recipientName, otpCode);
  const textContent =
    `Smarter AI — Verify your email address\n\n` +
    `Your 6-digit verification code is: ${otpCode}\n\n` +
    `This code expires in 5 minutes and can only be used once.\n` +
    `If you did not request a Smarter AI account, please ignore this email.`;

  const response = await fetch("https://api.brevo.com/v3/smtp/email", {
    method: "POST",
    headers: {
      "accept": "application/json",
      "content-type": "application/json",
      "api-key": apiKey,
    },
    body: JSON.stringify({
      sender: {
        name: senderName,
        email: senderEmail,
      },
      to: [
        {
          email: recipientEmail,
          name: recipientName || recipientEmail.split("@")[0],
        },
      ],
      subject: `${otpCode} is your Smarter AI verification code`,
      htmlContent,
      textContent,
    }),
  });

  if (!response.ok) {
    return {
      ok: false,
      error: "We couldn't send your verification code via Brevo. Please verify your Brevo API key and sender email configuration.",
    };
  }

  return { ok: true };
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  const url = new URL(req.url);
  const path = url.pathname.replace(/\/+$/, "");

  try {
    const brevo = getBrevoConfig();

    // 1. Health check endpoint
    if (req.method === "GET" && (path.endsWith("/health") || path.endsWith("/auth-otp"))) {
      return new Response(
        JSON.stringify({
          status: "healthy",
          service: "smarter-ai-auth-otp",
          otp_verification_enabled: Boolean(brevo.apiKey && brevo.senderEmail),
          brevo_configured: Boolean(brevo.apiKey && brevo.senderEmail),
          brevo_api_key_configured: Boolean(brevo.apiKey),
          brevo_sender_email_configured: Boolean(brevo.senderEmail),
          sender_name: brevo.senderName,
          supabase_admin_configured: Boolean(SUPABASE_URL && SUPABASE_SERVICE_ROLE_KEY),
          timestamp: new Date().toISOString(),
        }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
      return new Response(
        JSON.stringify({
          error: "Server authentication configuration is incomplete (SUPABASE_SERVICE_ROLE_KEY missing).",
        }),
        { status: 503, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const supabaseAdmin = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    // 2. Send / Resend 6-digit OTP
    if (req.method === "POST" && path.endsWith("/send-otp")) {
      if (!brevo.apiKey || !brevo.senderEmail) {
        return new Response(
          JSON.stringify({
            error: "Email delivery service is not configured on the server. Please configure BREVO_API_KEY and BREVO_SENDER_EMAIL in Supabase secrets.",
          }),
          { status: 503, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const body = await req.json();
      const email = String(body.email ?? "").trim().toLowerCase();
      const password = String(body.password ?? "");
      const displayName = String(body.display_name ?? "").trim();
      const isResend = Boolean(body.is_resend ?? false);
      const clientIp = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";

      if (!email || !email.includes("@")) {
        return new Response(
          JSON.stringify({ error: "Please enter a valid email address." }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      if (!isResend && password.length < 6) {
        return new Response(
          JSON.stringify({ error: "Password must be at least 6 characters." }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      if (
        !checkOtpRateLimit(`send:email:${email}`, MAX_OTP_SENDS_PER_WINDOW) ||
        !checkOtpRateLimit(`send:ip:${clientIp}`, MAX_OTP_SENDS_PER_WINDOW * 2)
      ) {
        return new Response(
          JSON.stringify({
            error: "Too many verification codes requested. Please wait a few minutes and try again.",
          }),
          { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const nowMs = Date.now();
      const { data: rows, error: selectErr } = await supabaseAdmin
        .from("email_verification_otps")
        .select("*")
        .eq("email", email)
        .eq("invalidated", false)
        .is("used_at", null)
        .order("created_at", { ascending: false })
        .limit(1);

      if (selectErr) {
        return new Response(
          JSON.stringify({
            error: "Database table email_verification_otps is not available. Please apply the OTP database migration first.",
          }),
          { status: 503, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const existingRecord = rows && rows.length > 0 ? (rows[0] as OtpRecord) : null;
      if (existingRecord) {
        const resendAtMs = new Date(existingRecord.resend_available_at).getTime();
        if (nowMs < resendAtMs) {
          const waitSec = Math.max(1, Math.ceil((resendAtMs - nowMs) / 1000));
          return new Response(
            JSON.stringify({
              error: `Please wait ${waitSec} seconds before requesting a new code.`,
              retry_after_seconds: waitSec,
            }),
            { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
          );
        }
      }

      let userId: string | null = existingRecord?.user_id ?? null;
      if (!userId) {
        const { data: latestAnyRows } = await supabaseAdmin
          .from("email_verification_otps")
          .select("user_id")
          .eq("email", email)
          .not("user_id", "is", null)
          .order("created_at", { ascending: false })
          .limit(1);
        if (latestAnyRows && latestAnyRows.length > 0) {
          userId = (latestAnyRows[0] as { user_id: string | null }).user_id;
        }
      }

      if (!isResend) {
        const { data: createdUser, error: createError } =
          await supabaseAdmin.auth.admin.createUser({
            email,
            password,
            email_confirm: false,
            user_metadata: { display_name: displayName || email.split("@")[0] },
          });

        if (createError) {
          const msg = (createError.message || "").toLowerCase();
          if (msg.includes("already") || msg.includes("registered") || msg.includes("exists")) {
            const { data: listData } = await supabaseAdmin.auth.admin.listUsers();
            const matchedUser = listData?.users?.find(
              (u: { email?: string }) => u.email?.toLowerCase() === email
            );
            if (matchedUser?.email_confirmed_at) {
              return new Response(
                JSON.stringify({
                  error: "An account with this email already exists. Please sign in instead.",
                }),
                { status: 409, headers: { ...corsHeaders, "Content-Type": "application/json" } }
              );
            }
            if (matchedUser) {
              userId = matchedUser.id;
              await supabaseAdmin.auth.admin.updateUserById(matchedUser.id, {
                password,
                user_metadata: { display_name: displayName || email.split("@")[0] },
              });
            }
          } else {
            return new Response(
              JSON.stringify({
                error: "Unable to create your account right now. Please check your details and try again.",
              }),
              { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
            );
          }
        } else if (createdUser?.user) {
          userId = createdUser.user.id;
        }
      } else if (!userId) {
        const { data: listData } = await supabaseAdmin.auth.admin.listUsers();
        const matchedUser = listData?.users?.find(
          (u: { email?: string }) => u.email?.toLowerCase() === email
        );
        if (matchedUser?.email_confirmed_at) {
          return new Response(
            JSON.stringify({
              error: "This email address is already verified. Please sign in.",
            }),
            { status: 409, headers: { ...corsHeaders, "Content-Type": "application/json" } }
          );
        }
        if (matchedUser) {
          userId = matchedUser.id;
        }
      }

      // Invalidate any previous active OTPs for this email
      await supabaseAdmin
        .from("email_verification_otps")
        .update({ invalidated: true })
        .eq("email", email)
        .eq("invalidated", false);

      const otpCode = generateSecureSixDigitOtp();
      const salt = generateHexSalt(16);
      const otpHash = await computeOtpHash(salt, email, otpCode);
      const recordId = crypto.randomUUID();
      const expiresAtIso = new Date(nowMs + OTP_EXPIRY_MS).toISOString();
      const resendAvailableAtIso = new Date(nowMs + OTP_RESEND_COOLDOWN_MS).toISOString();

      const { error: insertErr } = await supabaseAdmin.from("email_verification_otps").insert({
        id: recordId,
        email,
        user_id: userId,
        otp_hash: otpHash,
        salt,
        attempts: 0,
        max_attempts: OTP_MAX_ATTEMPTS,
        expires_at: expiresAtIso,
        resend_available_at: resendAvailableAtIso,
        invalidated: false,
        requester_ip: clientIp,
      });

      if (insertErr) {
        return new Response(
          JSON.stringify({
            error: "Failed to persist verification challenge. Please verify the email_verification_otps table migration.",
          }),
          { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const deliveryResult = await sendOtpEmailViaBrevo(email, displayName, otpCode);
      if (!deliveryResult.ok) {
        // Invalidate the unsent OTP record so the user is not locked out by cooldown
        await supabaseAdmin
          .from("email_verification_otps")
          .update({ invalidated: true })
          .eq("id", recordId);

        return new Response(
          JSON.stringify({
            error: deliveryResult.error || "We couldn't send your verification code. Please try again shortly.",
          }),
          { status: 502, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      return new Response(
        JSON.stringify({
          status: "otp_sent",
          email,
          user_id: userId,
          expires_in_seconds: 300,
          resend_cooldown_seconds: 45,
          max_attempts: OTP_MAX_ATTEMPTS,
          message: `We sent a 6-digit verification code to ${email}.`,
        }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 3. Verify 6-digit OTP
    if (req.method === "POST" && path.endsWith("/verify-otp")) {
      const body = await req.json();
      const email = String(body.email ?? "").trim().toLowerCase();
      const otpCode = String(body.otp_code ?? "").trim();
      const password = String(body.password ?? "");
      const clientIp = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";

      if (!email || !/^\d{6}$/.test(otpCode)) {
        return new Response(
          JSON.stringify({ error: "Please enter a valid 6-digit verification code." }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      if (
        !checkOtpRateLimit(`verify:email:${email}`, MAX_OTP_VERIFIES_PER_WINDOW) ||
        !checkOtpRateLimit(`verify:ip:${clientIp}`, MAX_OTP_VERIFIES_PER_WINDOW * 2)
      ) {
        return new Response(
          JSON.stringify({
            error: "Too many verification attempts. Please wait a few minutes and try again.",
          }),
          { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const { data: rows } = await supabaseAdmin
        .from("email_verification_otps")
        .select("*")
        .eq("email", email)
        .eq("invalidated", false)
        .is("used_at", null)
        .order("created_at", { ascending: false })
        .limit(1);

      const activeRecord = rows && rows.length > 0 ? (rows[0] as OtpRecord) : null;
      if (!activeRecord || activeRecord.invalidated || activeRecord.used_at) {
        return new Response(
          JSON.stringify({
            error: "This code is no longer valid. Please request a new code.",
          }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const nowMs = Date.now();
      if (nowMs > new Date(activeRecord.expires_at).getTime()) {
        await supabaseAdmin
          .from("email_verification_otps")
          .update({ invalidated: true })
          .eq("id", activeRecord.id);
        return new Response(
          JSON.stringify({
            error: "This code has expired. Request a new one.",
          }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      if (activeRecord.attempts >= activeRecord.max_attempts) {
        await supabaseAdmin
          .from("email_verification_otps")
          .update({ invalidated: true })
          .eq("id", activeRecord.id);
        return new Response(
          JSON.stringify({
            error: "Maximum verification attempts reached. Please request a new code.",
          }),
          { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const nextAttempts = activeRecord.attempts + 1;
      await supabaseAdmin
        .from("email_verification_otps")
        .update({ attempts: nextAttempts })
        .eq("id", activeRecord.id);

      const candidateHash = await computeOtpHash(activeRecord.salt, email, otpCode);
      const isMatch = timingSafeEqualHex(candidateHash, activeRecord.otp_hash);

      if (!isMatch) {
        const remaining = Math.max(0, activeRecord.max_attempts - nextAttempts);
        if (remaining === 0) {
          await supabaseAdmin
            .from("email_verification_otps")
            .update({ invalidated: true })
            .eq("id", activeRecord.id);
          return new Response(
            JSON.stringify({
              error: "Maximum verification attempts reached. Please request a new code.",
              remaining_attempts: 0,
            }),
            { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
          );
        }
        return new Response(
          JSON.stringify({
            error: `Incorrect verification code. ${remaining} ${remaining === 1 ? "attempt" : "attempts"} remaining.`,
            remaining_attempts: remaining,
          }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      const usedAtIso = new Date(nowMs).toISOString();
      const { data: consumedRows } = await supabaseAdmin
        .from("email_verification_otps")
        .update({ used_at: usedAtIso, invalidated: true })
        .eq("id", activeRecord.id)
        .is("used_at", null)
        .eq("invalidated", false)
        .select("id");

      if (!consumedRows || consumedRows.length === 0) {
        return new Response(
          JSON.stringify({
            error: "This verification code has already been used. Please sign in.",
          }),
          { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      if (activeRecord.user_id) {
        await supabaseAdmin.auth.admin.updateUserById(activeRecord.user_id, {
          email_confirm: true,
        });
      }

      if (SUPABASE_URL && SUPABASE_ANON_KEY && password) {
        const tokenResp = await fetch(`${SUPABASE_URL}/auth/v1/token?grant_type=password`, {
          method: "POST",
          headers: {
            "apikey": SUPABASE_ANON_KEY,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ email, password }),
        });

        if (tokenResp.ok) {
          const sessionJson = await tokenResp.json();
          return new Response(
            JSON.stringify({
              status: "verified",
              session: sessionJson,
            }),
            { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
          );
        }
      }

      return new Response(
        JSON.stringify({
          status: "verified",
          email,
          user_id: activeRecord.user_id,
        }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    return new Response(
      JSON.stringify({ error: `Unsupported route: ${req.method} ${path}` }),
      { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (_err) {
    return new Response(
      JSON.stringify({ error: "Verification service is temporarily unavailable. Please try again shortly." }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
