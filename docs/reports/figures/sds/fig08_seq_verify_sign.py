"""Figure 8. Sequence — verify the OTP and sign (UML / Visual Paradigm notation).

Frontend → ContractSigningController.verify → VerifyOtpAndSignUseCase.execute / verifyAndSign / claimKey /
deliveryScheduleTooSoon → SignerEligibility, ContractRepository, IdempotencyStore, OtpChallengeRepository,
SignatureRepository, ContractEventPublisher → Database, in backend/contract-service on main (26/11/2026), in that
order. Every exception has its own alt at the step that throws it; Spring hands it to GlobalExceptionHandler, which maps it to the HTTP status
and ErrorEnvelope code the Frontend receives. A refusal that must keep its side effect (killed code, counted attempt)
is returned from the transaction and thrown after the commit.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from seqdiagram import Message, Seq  # noqa: E402

s = Seq("fig08_seq_verify_sign", 1330, [
    ("P", "Buyer /\nSeller", "actor"),
    ("F", ":Frontend\n(React SPA)"),
    ("C", ":Contract\nSigning\nController"),
    ("H", ":Global\nException\nHandler"),
    ("U", ":VerifyOtp\nAndSign\nUseCase"),
    ("E", ":Signer\nEligibility"),
    ("R", ":Contract\nRepository"),
    ("I", ":Idempotency\nStore"),
    ("O", ":OtpChallenge\nRepository"),
    ("S", ":Signature\nRepository"),
    ("V", ":Contract\nEvent\nPublisher"),
    ("D", ":Database\n(contract_db)"),
])


def back(src, dst, text):
    """A dashed return that does not end an activation (an exception travelling up, a UI update)."""
    s.messages.append(Message(src, dst, text, s._advance(text), "reply"))


def error(thrower, exception, handler, status, codes, after_commit=False):
    f = s.begin("alt", exception, "P", "D")
    back(thrower, "H", ("return the refusal; thrown after commit: " if after_commit else "throw ") +
         exception.split("  [")[0] + "  → " + handler + "(ex)")
    back("H", "F", f"{status} ErrorEnvelope({codes})")
    back("F", "P", "show the error message")
    s.end(f)


s.call("P", "F", "1: enter the 6-digit code and press Confirm")
s.call("F", "C", "1.1: POST /api/v1/contracts/{id}/sign/verify (Idempotency-Key, otpId, otpCode)")
s.call("C", "U", "1.1.1: execute(command)")
s.call("U", "E", "1.1.1.1: requireFreshLogin(authTime)")
error("E", "SignerNotEligibleException", "handleSignerNotEligible", 403, "STEP_UP_REQUIRED")
s.done("E")
s.call("U", "R", "1.1.1.2: findById(contractId)")
s.call("R", "D", "1.1.1.2.1: SELECT … FROM contracts")
s.reply("D", "R", "row")
s.reply("R", "U", "Optional<Contract>")
error("U", "ContractNotFoundException  [no contract]", "handleNotFound", 404, "CONTRACT_NOT_FOUND")
error("U", "UnauthorizedContractAccessException  [contract.roleOf(actorId) is empty]", "handleUnauthorized", 403,
      "FORBIDDEN")
s.call("U", "E", "1.1.1.3: requireEligible(actorId)")
error("E", "SignerNotEligibleException", "handleSignerNotEligible", 403,
      "SIGNER_PROFILE_NOT_FOUND | SIGNER_NOT_VERIFIED | SIGNER_AUTHORIZATION_EXPIRED | SIGNER_LOCKED | SIGNER_EMAIL_MISSING")
s.reply("E", "U", "signer : UserInfo")
s.call("U", "E", "1.1.1.4: requireCounterpartyEligible(counterpartyId)   (D37)")
error("E", "ContractConflictException  [the other party is not eligible]", "handleContractConflict", 409,
      "COUNTERPARTY_NOT_ELIGIBLE")
s.done("E")

tx = s.begin("critical", "TransactionRunner.inTransaction(() → verifyAndSign(command, role))", "P", "D")
s.call("U", "R", "1.1.1.5: findByIdForUpdate(contractId)")
s.call("R", "D", "1.1.1.5.1: SELECT … FROM contracts FOR UPDATE")
s.reply("D", "R", "row (locked)")
s.reply("R", "U", "Optional<Contract>")
s.call("U", "I", "1.1.1.6: lookup(actorId, OPERATION, idempotencyKey, requestHash)")
s.call("I", "D", "1.1.1.6.1: SELECT request_hash FROM idempotency_record")
s.reply("D", "I", "stored hash")
s.reply("I", "U", "Optional<Outcome>")
error("U", "IdempotencyKeyReusedException  [outcome = CONFLICT]", "handleIdempotencyKeyReused", 409,
      "IDEMPOTENCY_KEY_REUSED")
rep = s.begin("opt", "outcome = REPLAY: same key, same request", "P", "D")
s.note("U", "return null: nothing is written again, the caller gets 204 again", side=1, dx=12)
s.end(rep)
s.call("U", "O", "1.1.1.7: findByIdForUpdate(otpId)")
s.call("O", "D", "1.1.1.7.1: SELECT … FROM signature_otp FOR UPDATE")
s.reply("D", "O", "row (locked)")
s.reply("O", "U", "Optional<OtpChallenge>")
error("U", "ContractConflictException  [no code, a code of another contract, or a cancel-request code]",
      "handleContractConflict", 409, "OTP_CHALLENGE_NOT_FOUND | OTP_CONTRACT_MISMATCH")
error("U", "UnauthorizedContractAccessException  [code of another user or of the other party]",
      "handleUnauthorized", 403, "FORBIDDEN")
old = s.begin("opt", "code already verified and the same code is sent with a new key (D58)", "P", "D")
s.call("U", "S", "1.1.1.8: findByOtpId(otpId)")
s.call("S", "D", "1.1.1.8.1: SELECT … FROM signature")
s.reply("D", "S", "row")
s.reply("S", "U", "Optional<Signature>")
s.call("U", "U", "1.1.1.9: claimKey(command, requestHash, signature)  → return null (204)")
s.end(old)
s.call("U", "U", "1.1.1.10: challenge.ensureUsable(now, maxAttempts)")
error("U", "ContractConflictException  [used, expired or locked code]", "handleContractConflict", 409,
      "OTP_ALREADY_USED | OTP_EXPIRED | OTP_ATTEMPTS_EXHAUSTED")
f = s.begin("alt", "challenge.signedContentHash ≠ current terms hash", "P", "D")
s.call("U", "O", "1.1.1.11: expire(otpId, now)")
s.call("O", "D", "1.1.1.11.1: UPDATE signature_otp\nSET expires_at = now")
s.done("D")
s.done("O")
back("U", "H", "return the refusal; thrown after commit: ContractConflictException  → handleContractConflict(ex)")
back("H", "F", "409 ErrorEnvelope(OTP_TERMS_CHANGED)")
back("F", "P", "show the error message")
s.end(f)
f = s.begin("alt", "wrong code", "P", "D")
s.call("U", "O", "1.1.1.12: recordFailedAttempt(otpId)")
s.call("O", "D", "1.1.1.12.1: UPDATE signature_otp\nSET attempt_count + 1")
s.done("D")
s.reply("O", "U", "attempts")
back("U", "H", "return the refusal; thrown after commit: ContractConflictException  → handleContractConflict(ex)")
back("H", "F", "409 ErrorEnvelope(OTP_INVALID | OTP_ATTEMPTS_EXHAUSTED)")
back("F", "P", "show the error and the attempts left")
s.end(f)
s.call("U", "U", "1.1.1.13: deliveryScheduleTooSoon(contract, role, now)")
s.note("U", "only for the second signature: calendar.businessDaysBefore(\nexpectedDeliveryDate, fundingLeadBusinessDays) < today (ICT)", side=1, dx=12)
error("U", "ContractConflictException  [a milestone funding date is already past (D67)]",
      "handleContractConflict", 409, "DELIVERY_SCHEDULE_TOO_SOON", after_commit=True)
s.call("U", "O", "1.1.1.14: markVerified(otpId, now)")
s.call("O", "D", "1.1.1.14.1: UPDATE signature_otp\nSET verified_at = now")
s.done("D")
s.done("O")
s.call("U", "U", "1.1.1.15: contract.sign(actorId, challenge, authTime, now, ipAddress, signatureId)")
error("U", "ContractConflictException  [contract no longer signable]", "handleContractConflict", 409,
      "CONTRACT_NOT_SIGNABLE | CONTRACT_ALREADY_SIGNED_BY_ACTOR | OTP_TERMS_CHANGED | RESERVED_QUANTITY_MISMATCH")
s.call("U", "S", "1.1.1.16: insert(signature)")
s.call("S", "D", "1.1.1.16.1: INSERT signature")
s.done("D")
s.done("S")
s.call("U", "R", "1.1.1.17: save(contract)")
s.call("R", "D", "1.1.1.17.1: UPDATE contracts")
s.done("D")
s.done("R")
signed = s.begin("opt", "contract.status = SIGNED (second signature)", "P", "D")
s.call("U", "V", "1.1.1.18: contractSigned(contract, now)")
s.call("V", "D", "1.1.1.18.1: INSERT\nevent_outbox\n(contract.signed)")
s.done("D")
s.done("V")
s.call("U", "V", "1.1.1.19: contractAnchorRequested(contract, now)")
s.call("V", "D", "1.1.1.19.1: INSERT\nevent_outbox\n(notification.\ncontract_anchor_\nrequested)")
s.done("D")
s.done("V")
s.end(signed)
s.call("U", "I", "1.1.1.20: claim(actorId, OPERATION, idempotencyKey, requestHash); recordResult(…, signatureId)")
s.call("I", "D", "1.1.1.20.1: INSERT idempotency_record;\nUPDATE result_ref = signatureId")
s.done("D")
s.done("I")
s.end(tx)
s.reply("U", "C", "return")
s.reply("C", "F", "204 No Content")
s.reply("F", "P", "reload the contract: signed, or SIGNED when both parties have signed")

if __name__ == "__main__":
    s.write(Path(__file__).resolve().parent)
