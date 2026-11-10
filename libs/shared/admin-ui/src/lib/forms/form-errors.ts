import type { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import {
  type FieldTree,
  isFieldTree,
  type SchemaPath,
  validate,
  type ValidationError,
} from '@angular/forms/signals';
import {
  checkFieldRules,
  type FieldError,
  fieldErrors,
  fieldPath,
  type FieldRules,
  type Problem,
  ruleMessage,
  type RuleViolation,
} from '@marketplace/shared-web-util';

/** Chave do erro que veio no 400 do servidor, com a mensagem dele. */
export const SERVER_ERROR = 'server';

/** Chave do erro dos limites do contrato (`schemaValidator`), com o limite desrespeitado. */
export const SCHEMA_ERROR = 'schema';

export interface ServerErrorOptions {
  /** Caminho do controle para o campo do servidor quando os nomes diferem, como `{ categoryId: 'category' }`. */
  readonly fields?: Readonly<Record<string, string>>;
}

/**
 * Põe cada erro do 400 (`errors`, com `field` e `message`) no controle do campo de um formulário reativo, como o erro
 * `server`, e marca o controle como tocado para a mensagem aparecer. O erro sai quando a pessoa muda o valor, porque o
 * Angular refaz a validação. Devolve os erros sem controle no formulário, e o erro sem campo sem `field`, para o
 * alerta da tela.
 */
export function applyServerErrors(
  form: AbstractControl,
  problem: Problem | null | undefined,
  options: ServerErrorOptions = {},
): FieldError[] {
  const unmatched: FieldError[] = [];
  for (const [field, message] of Object.entries(fieldErrors(problem))) {
    const path = controlPath(field, options);
    const control = path.length ? form.get(path) : null;
    if (control) {
      control.setErrors({ ...control.errors, [SERVER_ERROR]: message });
      control.markAsTouched();
    } else {
      unmatched.push(field ? { field, message } : { message });
    }
  }
  return unmatched;
}

/**
 * Erros do 400 para o `submit` de um formulário de signals (`@angular/forms/signals`): cada um vai para o campo do
 * caminho, e o que não tem campo no formulário vai para a raiz.
 */
export function serverFieldErrors(
  form: FieldTree<unknown>,
  problem: Problem | null | undefined,
  options: ServerErrorOptions = {},
): ValidationError.WithOptionalFieldTree[] {
  return Object.entries(fieldErrors(problem)).map(([field, message]) => {
    const fieldTree = findField(form, controlPath(field, options));
    return fieldTree ? { kind: SERVER_ERROR, message, fieldTree } : { kind: SERVER_ERROR, message };
  });
}

/** Validador reativo com os limites do contrato de um campo (`fieldRules`), com o erro `schema`. */
export function schemaValidator(rules: FieldRules): ValidatorFn {
  return (control) => {
    const violation = checkFieldRules(rules, control.value);
    return violation ? { [SCHEMA_ERROR]: violation } : null;
  };
}

/** Os limites do contrato de um campo num formulário de signals, com a mensagem pronta no erro `schema`. */
export function validateSchemaRules<TValue>(path: SchemaPath<TValue>, rules: FieldRules): void {
  validate(path, ({ value }) => {
    const violation = checkFieldRules(rules, value());
    return violation ? { kind: SCHEMA_ERROR, message: ruleMessage(violation) } : undefined;
  });
}

/**
 * Mensagem para mostrar embaixo do campo reativo: a do servidor, a do limite do contrato ou a dos validadores do
 * Angular, nesta ordem. A tela mostra quando o campo foi tocado.
 */
export function controlMessage(control: AbstractControl | null | undefined): string | null {
  const errors = control?.errors;
  if (!errors) {
    return null;
  }
  if (typeof errors[SERVER_ERROR] === 'string') {
    return errors[SERVER_ERROR];
  }
  const violation = (errors[SCHEMA_ERROR] as RuleViolation | undefined) ?? angularViolation(errors);
  return violation ? ruleMessage(violation) : 'Confira este campo.';
}

function angularViolation(errors: ValidationErrors): RuleViolation | null {
  if (errors['required']) {
    return { kind: 'required' };
  }
  if (errors['maxlength']) {
    return { kind: 'maxLength', limit: errors['maxlength'].requiredLength };
  }
  if (errors['minlength']) {
    return { kind: 'minLength', limit: errors['minlength'].requiredLength };
  }
  if (errors['max']) {
    return { kind: 'maximum', limit: errors['max'].max };
  }
  if (errors['min']) {
    return { kind: 'minimum', limit: errors['min'].min };
  }
  return errors['pattern'] ? { kind: 'pattern' } : null;
}

function controlPath(field: string, options: ServerErrorOptions): string[] {
  const mapped = options.fields?.[field];
  return mapped === undefined ? fieldPath(field) : fieldPath(mapped);
}

function findField(
  form: FieldTree<unknown>,
  path: readonly string[],
): FieldTree<unknown> | undefined {
  let node: unknown = form;
  for (const segment of path) {
    node = isFieldTree(node) ? (node as unknown as Record<string, unknown>)[segment] : undefined;
  }
  return path.length && isFieldTree(node) ? node : undefined;
}
