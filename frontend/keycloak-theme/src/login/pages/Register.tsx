import { Button } from "@/shared/ui/button";
import { Banner } from "@/shared/ui/banner";
import { AuthLayout } from "../AuthLayout";
import { useKeycloakForm, TextInput, z } from "../form";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

export default function Register({
  kcContext,
  i18n,
}: {
  kcContext: Extract<KcContext, { pageId: "register.ftl" }>;
  i18n: I18n;
}) {
  const { msgStr } = i18n;
  const { url, profile, passwordRequired } = kcContext;
  // D47: the realm role is picked here; the choices come from the realm `accountType` options.
  const accountType = profile.attributesByName.accountType;
  const roleOptions = accountType?.validators.options?.options ?? [];
  const schema = z
    .object({
      email: z.email(msgStr("invalidEmail")),
      accountType:
        roleOptions.length > 0
          ? z.string().refine((value) => roleOptions.includes(value), {
              message: msgStr("accountTypeRequired"),
            })
          : z.string(),
      password: z
        .string()
        .min(passwordRequired ? 1 : 0, msgStr("requiredField")),
      "password-confirm": z.string(),
    })
    .refine((data) => data.password === data["password-confirm"], {
      message: msgStr("passwordMismatch"),
      path: ["password-confirm"],
    });
  const form = useKeycloakForm(schema, {
    email: profile.attributesByName.email?.value ?? "",
    accountType: accountType?.value ?? "",
    password: "",
    "password-confirm": "",
  });
  // Do not hide mandatory server requirements or invent first/last names to bypass them.
  const unsupportedProfile = Object.values(profile.attributesByName).some(
    (attribute) =>
      attribute.required &&
      !["email", "username"].includes(attribute.name) &&
      !(attribute.name === "accountType" && roleOptions.length > 0),
  );
  const roleError = form.error("accountType", kcContext);
  const unavailable =
    !kcContext.realm.registrationEmailAsUsername ||
    unsupportedProfile ||
    kcContext.recaptchaRequired ||
    kcContext.termsAcceptanceRequired;
  return (
    <AuthLayout
      kcContext={kcContext}
      i18n={i18n}
      title={msgStr("registerTitle")}
      description={msgStr("registerDescription")}
    >
      {unavailable ? (
        <Banner variant="warning" role="alert">
          {msgStr("registrationUnavailable")}
        </Banner>
      ) : (
        <form
          id="kc-register-form"
          action={url.registrationAction}
          method="post"
          onSubmit={form.onSubmit}
        >
          <TextInput
            {...form.field("email", kcContext)}
            type="email"
            label={msgStr("email")}
            autoComplete="email"
            autoFocus
            required
          />
          {roleOptions.length > 0 && (
            <fieldset
              className="flex flex-col"
              aria-describedby={roleError ? "accountType-feedback" : undefined}
            >
              <legend className="mb-1.5 text-[13px] text-text-primary">
                {msgStr("accountTypeLabel")}{" "}
                <span className="text-danger">*</span>
              </legend>
              <div className="grid grid-cols-2 gap-3">
                {roleOptions.map((option) => (
                  <label key={option} className="auth-role-option">
                    <input
                      type="radio"
                      value={option}
                      required
                      aria-invalid={!!roleError}
                      {...form.register("accountType")}
                    />
                    <span>
                      {accountType?.annotations.inputOptionLabels?.[option] ??
                        option}
                    </span>
                  </label>
                ))}
              </div>
              {roleError ? (
                <div
                  id="accountType-feedback"
                  aria-live="polite"
                  className="mt-1 text-[13px] text-danger"
                >
                  {roleError}
                </div>
              ) : null}
            </fieldset>
          )}
          {passwordRequired && (
            <>
              <TextInput
                {...form.field("password", kcContext)}
                type="password"
                label={msgStr("password")}
                autoComplete="new-password"
                required
              />
              <TextInput
                {...form.field("password-confirm", kcContext)}
                type="password"
                label={msgStr("passwordConfirm")}
                autoComplete="new-password"
                required
              />
            </>
          )}
          <Banner variant="info">{msgStr("registrationNextStep")}</Banner>
          <Button
            className="auth-submit"
            type="submit"
            disabled={form.submitting}
          >
            {msgStr(form.submitting ? "submitting" : "doRegister")}
          </Button>
        </form>
      )}
      <p className="text-center text-[13px] text-text-secondary">
        {msgStr("alreadyHaveAccount")}{" "}
        <a href={url.loginUrl}>{msgStr("doLogIn")}</a>
      </p>
    </AuthLayout>
  );
}
