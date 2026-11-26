"""BF-01 User Onboarding & KYC (activity diagram, UML / Visual Paradigm notation).

Content synced with the Phase 2 design (#662): self-registration as Buyer or Seller (D47), e-mail OTP verification
before KYC review, scheduled cleanup of unverified accounts (D59). Final nodes sit in the user's lane.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from vpdiagram import Diagram  # noqa: E402

d = Diagram("bf01", width=760, height=1085)
for name, w in (("Guest / User", 250), ("System", 260), ("Operator", 250)):
    d.lane(name, w)
G, Y, O = d.center("Guest / User"), d.center("System"), d.center("Operator")

d.initial("start", G, 60)
d.action("a1", G, 125, "1. Register with e-mail,\npassword and the Buyer\nor Seller role", w=200)
d.action("a2", Y, 125, "2. Create the Keycloak\naccount and assign\nthe selected role", w=210)
d.action("a3", G, 215, "3. Open onboarding; submit\norganization details and\nsupporting documents", w=220)
d.action("a4", Y, 295, "4. Create the PENDING\nprofile; e-mail a\nverification OTP", w=210)
d.action("a5", G, 375, "5. Enter the OTP or\nrequest a new code", w=200)
d.action("a6", Y, 455, "6. Validate the OTP", w=210)
d.note("n1", O, 455, "E-mail is prefilled, read-only.\nOTP: 6 digits, valid 10 min,\n5 attempts, 60 s resend\ncooldown; a failed send can\nbe resent. KYC review needs\na verified e-mail.", w=215)
d.decision("d1", Y, 520)
d.action("a7", 85, 520, "7. Show the error;\nallow a resend", w=150)
d.action("a8", Y, 600, "8. Mark the e-mail verified;\nthe profile enters the\nKYC review queue", w=220)
d.action("a9", O, 680, "9. Review the documents\nand the authority\nto represent", w=200)
d.decision("d2", O, 755)
d.action("a10", O, 835, "10. Verify KYC; record the\nauthorization expiry\n(or indefinite)", w=210)
d.action("a11", Y, 835, "11. Set VERIFIED, e-mail\nthe result; trading is\nallowed if eligible", w=210)
d.final_named("f1", G, 835, "Account verified", side="b")
d.action("a12", O, 935, "12. Reject with\na reason", w=150)
d.action("a13", Y, 935, "13. Set REJECTED, e-mail\nthe rejection reason", w=210)
d.final_named("f2", G, 935, "Account not verified", side="b")
d.note("n2", 380, 1030, "Scheduled cleanup (D59), independent of this flow: a self-registered Buyer or Seller whose e-mail is\n"
       "still unverified 24 h after the Keycloak account was created is deleted with its profile. Verified, staff\n"
       "and seed accounts are excluded; resending the OTP does not extend the 24 h. Before KYC approval the\n"
       "user can sign in, but eligibility-gated trading actions are blocked.", w=720)

d.flow("start", "b", "a1", "t")
d.flow("a1", "r", "a2", "l")
d.flow("a2", "b", "a3", "r", (Y, 215))
d.flow("a3", "b", "a4", "l", (G, 295))
d.flow("a4", "b", "a5", "r", (Y, 375))
d.flow("a5", "b", "a6", "l", (G + 40, 455), s_off=40)
d.edge("n1", "a6", d.nodes["n1"].port("l"), d.nodes["a6"].port("r"), dashed=True, arrow=False)
d.flow("a6", "b", "d1", "t")
d.flow("d1", "l", "a7", "r", label="[invalid]", label_at=(178, 502))
d.flow("a7", "t", "a5", "b", d_off=85 - G)
d.flow("d1", "b", "a8", "t", label="[valid]", label_at=(Y + 6, 537))
d.flow("a8", "r", "a9", "t", (O, 600))
d.flow("a9", "b", "d2", "t")
d.flow("d2", "b", "a10", "t", label="[valid]", label_at=(O + 6, 772))
d.flow("a10", "l", "a11", "r")
d.flow("a11", "l", "f1", "r")
d.flow("d2", "r", "a12", "r", (746, 755), (746, 935), label="[invalid]", label_at=(O + 22, 737))
d.flow("a12", "l", "a13", "r")
d.flow("a13", "l", "f2", "r")

if __name__ == "__main__":
    d.write(Path(__file__).resolve().parent)
