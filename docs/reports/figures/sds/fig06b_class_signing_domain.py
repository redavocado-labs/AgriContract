"""Figure 6b. Contract signing — domain model (class diagram, UML / Visual Paradigm notation).

Names are the ones in backend/contract-service/src/main/java/com/agricontract/contract/domain/model on main
(10/11/2026): Contract, signature/{OtpChallenge,Signature}, consent/{ConsentEvidence,SignerRole,ConsentActionType},
terms/CanonicalTerms, vo/ContractStatus. Contract shows the fields and methods of the signing path; records show
all their components. Static members are underlined.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from vpdiagram import Diagram  # noqa: E402

d = Diagram("fig06b_class_signing_domain", width=722, height=1200, allow_crossings=True)
L, R, WL, WR = 170, 535, 300, 310  # columns 20-320 and 380-690, channel 320-380

d.klass("contract", L, 20, "Contract", "entity", attrs=[
    "- contractId : ContractId",
    "- listingId : String",
    "- buyerId : String",
    "- sellerId : String",
    "- coffeeTerms : CanonicalTerms",
    "- status : ContractStatus",
    "- version : Long",
    "- signedHashes : Map<SignerRole, String>",
    "- reservedQuantity : BigDecimal",
    "- pendingTerminalDecisionId : String",
    "- pendingTerminalStatus : ContractStatus",
    "- settledAt : Instant",
], ops=[
    "+ reviseCoffeeTerms(userId : String,",
    "      revised : CanonicalTerms) : void",
    "+ consentToSign(userId : String,",
    "      claimedRole : SignerRole,",
    "      presentedStatementVersion : String,",
    "      presentedContentHash : String,",
    "      statements : ConsentStatements,",
    "      consentId : UUID, consentedAt : Instant,",
    "      correlationId : String) : ConsentEvidence",
    "+ sign(userId : String, verified : OtpChallenge,",
    "      authTime : Instant, signedAt : Instant,",
    "      ipAddress : String, signatureId : UUID)",
    "      : Signature",
    "+ roleOf(userId : String) : Optional<SignerRole>",
    "+ getSignedRoles() : Set<SignerRole>",
    "+ getReservedQuantity() : BigDecimal",
    "+ withdraw(userId : String) : SignerRole",
    "+ activate() : void",
], w=WL)
d.klass("terms", L, 610, "CanonicalTerms", "value object", attrs=[
    "+ snapshot : ContractTermsSnapshot",
    "+ canonicalJson : String",
    "+ signedContentHash : String",
], ops=[
    "__+ of(snapshot : ContractTermsSnapshot,",
    "__      canonicalizer : TermsCanonicalizer)",
    "__      : CanonicalTerms",
    "__+ restore(storedJson : String, storedHash : String,",
    "__      canonicalizer : TermsCanonicalizer)",
    "__      : CanonicalTerms",
    "__+ sha256(canonicalJson : String) : String",
], w=WL)
d.klass("status", L, 850, "ContractStatus", "enumeration", attrs=[
    "OFFERED", "NEGOTIATING", "SIGNED", "ACTIVE", "SETTLED", "ACTIVATION_REFUND_PENDING", "ACTIVATION_FAILED",
    "WITHDRAWN"], ops=None, w=WL)
d.klass("role", L, 1030, "SignerRole", "enumeration", attrs=["BUYER", "SELLER"], ops=None, w=130)
d.klass("action", R, 1000, "ConsentActionType", "enumeration", attrs=[
    "SIGN_CONTRACT", "CANCEL_ACTIVATION", "DECLARE_SELLER_WEIGHT", "RECORD_BUYER_RECEIPT", "CONFIRM_CLEAN_RECEIPT",
    "FLAG_MILESTONE_ISSUE", "CONTEST_MILESTONE_FLAG", "CONCEDE_DISPUTE", "SETTLE_DISPUTE"], ops=None, w=190)

d.klass("signature", R, 20, "Signature", "entity", attrs=[
    "+ signatureId : UUID",
    "+ contractId : String",
    "+ signerRole : SignerRole",
    "+ signerUserId : String",
    "+ otpId : UUID",
    "+ consentId : UUID",
    "+ authTime : Instant",
    "+ signedAt : Instant",
    "+ signedContentHash : String",
    "+ ipAddress : String",
], w=WR)
d.klass("consent", R, 290, "ConsentEvidence", "entity", attrs=[
    "+ consentId : UUID",
    "+ actionType : ConsentActionType",
    "+ subjectType : ConsentSubjectType",
    "+ subjectId : String",
    "+ actorUserId : String",
    "+ actorRole : SignerRole",
    "+ statementCode : String",
    "+ statementVersion : String",
    "+ statementHash : String",
    "+ presentedContentHash : String",
    "+ consentedAt : Instant",
    "+ correlationId : String",
], w=WR)
d.klass("otp", R, 600, "OtpChallenge", "entity", attrs=[
    "+ otpId : UUID",
    "+ consentId : UUID",
    "+ contractId : String",
    "+ signerRole : SignerRole",
    "+ userId : String",
    "+ authTime : Instant",
    "+ signedContentHash : String",
    "+ otpHash : String",
    "+ sentAt : Instant",
    "+ expiresAt : Instant",
    "+ verifiedAt : Instant",
    "+ attemptCount : int",
    "+ actionType : ConsentActionType",
], ops=[
    "__+ issue(otpId : UUID, consentId : UUID,",
    "__      contractId : String, signerRole : SignerRole,",
    "__      userId : String, authTime : Instant,",
    "__      signedContentHash : String, otpHash : String,",
    "__      now : Instant, expirySeconds : long,",
    "__      actionType : ConsentActionType) : OtpChallenge",
    "+ isUsable(now : Instant, maxAttempts : int) : boolean",
    "+ ensureUsable(now : Instant, maxAttempts : int) : void",
], w=WR)

n = d.nodes
def y(node, frac):
    x0, y0, x1, y1 = n[node].box()
    return y0 + (y1 - y0) * frac

d.rel("composition", "contract", "b", "terms", "t")
d.rel("dependency", "contract", "r", "signature", "l", s_off=y("signature", .4) - n["contract"].cy,
      d_off=y("signature", .4) - n["signature"].cy)
d.rel("dependency", "contract", "r", "consent", "l", s_off=y("consent", .35) - n["contract"].cy,
      d_off=y("consent", .35) - n["consent"].cy)
d.rel("association", "signature", "b", "consent", "t")
d.rel("association", "otp", "t", "consent", "b")
d.rel("association", "signature", "r", "otp", "r", (706, y("signature", .5)), (706, y("otp", .08)),
      d_off=y("otp", .08) - n["otp"].cy)

if __name__ == "__main__":
    d.write(Path(__file__).resolve().parent)
