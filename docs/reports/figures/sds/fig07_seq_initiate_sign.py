"""Figure 7. Sequence — initiate sign (UML / Visual Paradigm notation).

Frontend → ContractSigningController.initiate → InitiateSignUseCase.execute / issueOrReplay → SignerEligibility,
IdempotencyStore, ContractRepository, SigningConsentRecorder.record, OtpChallengeIssuer.issue, OtpEmailPort.send
→ Database, in backend/contract-service on main (26/11/2026), in that order. Every exception has its own alt at the
step that throws it: Spring hands it to GlobalExceptionHandler, which maps it to the HTTP status and ErrorEnvelope code the Frontend receives.
Message, parameter, table and error-code names are the code's.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from seqdiagram import Message, Seq  # noqa: E402

s = Seq("fig07_seq_initiate_sign", 1330, [
    ("P", "Buyer /\nSeller", "actor"),
    ("F", ":Frontend\n(React SPA)"),
    ("C", ":Contract\nSigning\nController"),
    ("H", ":Global\nException\nHandler"),
    ("U", ":InitiateSign\nUseCase"),
    ("E", ":Signer\nEligibility"),
    ("I", ":Idempotency\nStore"),
    ("R", ":Contract\nRepository"),
    ("K", ":Signing\nConsent\nRecorder"),
    ("O", ":Otp\nChallenge\nIssuer"),
    ("M", ":OtpEmail\nPort"),
    ("D", ":Database\n(contract_db)"),
])


def back(src, dst, text):
    """A dashed return that does not end an activation (an exception travelling up, a UI update)."""
    s.messages.append(Message(src, dst, text, s._advance(text), "reply"))


def error(thrower, exception, handler, status, codes):
    f = s.begin("alt", exception, "P", "D")
    back(thrower, "H", "throw " + exception.split("  [")[0] + "  → " + handler + "(ex)")
    back("H", "F", f"{status} ErrorEnvelope({codes})")
    back("F", "P", "show the error message")
    s.end(f)


s.call("P", "F", "1: tick the consent and press Sign")
s.call("F", "C", "1.1: POST /api/v1/contracts/{id}/sign/initiate\n(Idempotency-Key, signerRole, consentStatementVersion, presentedContentHash)")
s.call("C", "U", "1.1.1: execute(command)")
s.call("U", "E", "1.1.1.1: requireFreshLogin(authTime)")
error("E", "SignerNotEligibleException", "handleSignerNotEligible", 403, "STEP_UP_REQUIRED")
s.done("E")
s.call("U", "E", "1.1.1.2: requireEligible(actorId)")
error("E", "SignerNotEligibleException", "handleSignerNotEligible", 403,
      "SIGNER_PROFILE_NOT_FOUND | SIGNER_NOT_VERIFIED | SIGNER_AUTHORIZATION_EXPIRED | SIGNER_LOCKED | SIGNER_EMAIL_MISSING")
s.reply("E", "U", "signer : UserInfo")

tx = s.begin("critical", "TransactionRunner.inTransaction(() → issueOrReplay(command))", "P", "D")
s.call("U", "I", "1.1.1.3: claim(actorId, OPERATION, idempotencyKey, requestHash)")
s.call("I", "D", "1.1.1.3.1: INSERT idempotency_record (duplicate key → SELECT request_hash)")
s.reply("D", "I", "row / stored hash")
s.reply("I", "U", "outcome : NEW | REPLAY | CONFLICT")
error("U", "IdempotencyKeyReusedException  [outcome = CONFLICT]", "handleIdempotencyKeyReused", 409,
      "IDEMPOTENCY_KEY_REUSED")
alt = s.begin("alt", "outcome = REPLAY", "U", "D")
s.call("U", "I", "1.1.1.4: resultOf(actorId, OPERATION, idempotencyKey)")
s.call("I", "D", "1.1.1.4.1: SELECT result_ref FROM idempotency_record")
s.reply("D", "I", "otpId")
s.reply("I", "U", "otpId")
s.note("U", "challenges.findById(otpId);\nchallenge.isUsable(now, maxAttempts)", side=1, dx=12)
s.separator(alt, "outcome = NEW")
s.call("U", "R", "1.1.1.5: findById(contractId)")
s.call("R", "D", "1.1.1.5.1: SELECT … FROM contracts")
s.reply("D", "R", "row")
s.reply("R", "U", "Optional<Contract>")
s.end(alt)
error("U", "ContractConflictException  [replayed challenge not usable]", "handleContractConflict", 409,
      "OTP_CHALLENGE_NOT_ACTIVE")
error("R", "ContractNotFoundException  [no contract]", "handleNotFound", 404, "CONTRACT_NOT_FOUND")
s.call("U", "K", "1.1.1.6: record(contract, actorId, signerRole, statementVersion, presentedContentHash, correlationId)")
s.note("K", "contract.consentToSign(…) checks: caller is the party, coffee terms exist,\nstatus OFFERED | NEGOTIATING, not signed yet, current statement version,\npresentedContentHash = terms hash", side=-1, dx=12)
error("K", "UnauthorizedContractAccessException  [caller is not that party]", "handleUnauthorized", 403, "FORBIDDEN")
error("K", "ContractConflictException  [a check fails]", "handleContractConflict", 409,
      "CONTRACT_HAS_NO_COFFEE_TERMS | CONTRACT_NOT_SIGNABLE | CONTRACT_ALREADY_SIGNED_BY_ACTOR |\nCONSENT_STATEMENT_OUTDATED | PRESENTED_CONTENT_HASH_MISMATCH")
s.call("K", "D", "1.1.1.6.1: INSERT consent_evidence")
s.done("D")
s.reply("K", "U", "consent : ConsentEvidence")
s.call("U", "O", "1.1.1.7: issue(contract, consent, authTime)")
s.call("O", "D", "1.1.1.7.1: UPDATE signature_otp\n(expire usable codes);\nSELECT MAX(sent_at),\nCOUNT last hour")
s.reply("D", "O", "lastSentAt, sentLastHour")
error("O", "ContractConflictException  [cooldown 60 s / 5 per hour]", "handleContractConflict", 409,
      "OTP_RESEND_TOO_SOON | OTP_RESEND_LIMIT_REACHED")
s.call("O", "D", "1.1.1.7.2: INSERT signature_otp\n(HMAC of the code,\nexpires in 300 s)")
s.done("D")
s.reply("O", "U", "otp : IssuedOtp")
s.call("U", "I", "1.1.1.8: recordResult(actorId, OPERATION, idempotencyKey, otpId)")
s.call("I", "D", "1.1.1.8.1: UPDATE idempotency_record SET result_ref = otpId")
s.done("D")
s.done("I")
s.end(tx)

sent = s.begin("opt", "fresh challenge (not a replay); after commit", "P", "D")
s.call("U", "M", "1.1.1.9: send(OtpEmail)")
s.done("M")
fail = s.begin("alt", "OtpEmailNotSentException", "P", "D")
s.call("U", "O", "1.1.1.10: expire(otpId)")
s.call("O", "D", "1.1.1.10.1: UPDATE signature_otp\nSET expires_at = now")
s.done("D")
s.done("O")
back("U", "H", "throw OtpEmailNotSentException  → handleOtpEmailNotSent(ex)")
back("H", "F", "502 ErrorEnvelope(OTP_EMAIL_NOT_SENT)")
back("F", "P", "show the error message")
s.end(fail)
s.end(sent)
s.reply("U", "C", "SignChallengeView(otpId, expiresAt)")
s.reply("C", "F", "200 InitiateSignResponse(otpId, expiresAt)")
s.reply("F", "P", "show the OTP input and the expiry countdown")

if __name__ == "__main__":
    s.write(Path(__file__).resolve().parent)
