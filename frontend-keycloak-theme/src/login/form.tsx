import { useState, type FormEvent } from "react";
import {
  useForm,
  type FieldValues,
  type DefaultValues,
  type Path,
} from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { TextInput, type TextInputProps } from "@/shared/ui/text-input";
import type { KcContext } from "./KcContext";

export function useKeycloakForm<T extends FieldValues>(
  schema: z.ZodType<T, T>,
  defaultValues: DefaultValues<T>,
) {
  const form = useForm<T>({ resolver: zodResolver(schema), defaultValues });
  const [submitting, setSubmitting] = useState(false);
  function onSubmit(event: FormEvent<HTMLFormElement>) {
    const element = event.currentTarget;
    // Submit the original HTML form to Keycloak; never call a token endpoint.
    void form.handleSubmit(() => {
      if (submitting) return;
      setSubmitting(true);
      HTMLFormElement.prototype.submit.call(element);
    })(event);
  }
  function error(
    name: Path<T>,
    kcContext: KcContext,
    serverField = name as string,
  ): string | undefined {
    const clientError = form.formState.errors[name]?.message;
    if (typeof clientError === "string") return clientError;
    return kcContext.messagesPerField.existsError(serverField)
      ? kcContext.messagesPerField.get(serverField)
      : undefined;
  }
  function field(
    name: Path<T>,
    kcContext: KcContext,
    serverField = name as string,
  ): TextInputProps {
    const { ref, ...registration } = form.register(name);
    return {
      ...registration,
      inputRef: ref,
      id: name,
      error: error(name, kcContext, serverField),
    };
  }
  return { onSubmit, submitting, field, error, register: form.register };
}

export { TextInput, z };
