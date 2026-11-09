import { Injector, runInInjectionContext, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormArray, FormControl, FormGroup, Validators } from '@angular/forms';
import { form } from '@angular/forms/signals';
import type { FieldRules, Problem } from '@marketplace/shared-web-util';

import {
  applyServerErrors,
  controlMessage,
  SCHEMA_ERROR,
  schemaValidator,
  SERVER_ERROR,
  serverFieldErrors,
  validateSchemaRules
} from './form-errors';

const invalid: Problem = {
  type: 'https://marketplace.dev/problems/validation-failed',
  title: 'Dados inválidos',
  status: 400,
  errors: [
    { field: 'title', message: 'não deve estar em branco' },
    { field: 'title', message: 'tamanho deve ser entre 0 e 200' },
    { field: 'categoryId', message: 'não deve ser nulo' },
    { field: 'skus[0].sellerSku', message: 'deve corresponder a "[A-Za-z0-9]..."' },
    { field: 'currency', message: 'não deve ser nulo' }
  ]
};

const titleRules: FieldRules = { required: true, pattern: '\\S', maxLength: 10 };

describe('erros do formulário reativo', () => {
  const build = () =>
    new FormGroup({
      title: new FormControl('', { nonNullable: true }),
      category: new FormControl<string | null>(null),
      skus: new FormArray([new FormGroup({ sellerSku: new FormControl('cam p', { nonNullable: true }) })])
    });

  it('põe a primeira mensagem do servidor em cada campo e devolve os campos sem controle', () => {
    const product = build();
    const unmatched = applyServerErrors(product, invalid, { fields: { categoryId: 'category' } });

    expect(product.controls.title.errors).toEqual({ [SERVER_ERROR]: 'não deve estar em branco' });
    expect(product.controls.title.touched).toBe(true);
    expect(product.controls.category.errors).toEqual({ [SERVER_ERROR]: 'não deve ser nulo' });
    expect(product.get(['skus', 0, 'sellerSku'])?.errors?.[SERVER_ERROR]).toContain('deve corresponder');
    expect(unmatched).toEqual([{ field: 'currency', message: 'não deve ser nulo' }]);
  });

  it('tira o erro do servidor quando a pessoa muda o valor', () => {
    const product = build();
    applyServerErrors(product, invalid);
    product.controls.title.setValue('Camiseta');
    expect(product.controls.title.errors).toBeNull();
  });

  it('manda o erro sem campo para o alerta e ignora o erro sem mensagem', () => {
    const product = build();
    const problem: Problem = { status: 400, errors: [{ field: 'title' }, { message: 'Confira os dados.' }] };

    expect(applyServerErrors(product, problem)).toEqual([{ message: 'Confira os dados.' }]);
    expect(product.controls.title.errors).toBeNull();
  });

  it('não faz nada sem problema', () => {
    const product = build();
    expect(applyServerErrors(product, null)).toEqual([]);
    expect(product.controls.title.errors).toBeNull();
  });

  it('confere os limites do contrato e escreve a mensagem do campo', () => {
    const title = new FormControl('', { nonNullable: true, validators: [schemaValidator(titleRules)] });
    expect(title.errors).toEqual({ [SCHEMA_ERROR]: { kind: 'required' } });
    expect(controlMessage(title)).toBe('Preencha este campo.');

    title.setValue('Camiseta Basic');
    expect(controlMessage(title)).toBe('Use no máximo 10 caracteres.');

    title.setValue('Camiseta');
    expect(controlMessage(title)).toBeNull();
  });

  it('dá preferência à mensagem do servidor e cobre os validadores do Angular', () => {
    const title = new FormControl('', { nonNullable: true, validators: [Validators.required] });
    applyServerErrors(new FormGroup({ title }), invalid);
    expect(controlMessage(title)).toBe('não deve estar em branco');

    expect(controlMessage(new FormControl('', Validators.required))).toBe('Preencha este campo.');
    expect(controlMessage(new FormControl('abcdef', Validators.maxLength(3)))).toBe('Use no máximo 3 caracteres.');
    expect(controlMessage(new FormControl('a', Validators.minLength(3)))).toBe('Use pelo menos 3 caracteres.');
    expect(controlMessage(new FormControl(12, Validators.max(10)))).toBe('Use um valor de até 10.');
    expect(controlMessage(new FormControl(-1, Validators.min(0)))).toBe('Use um valor a partir de 0.');
    expect(controlMessage(new FormControl('x', Validators.pattern('[0-9]+')))).toBe('Confira o formato deste campo.');
    expect(controlMessage(new FormControl('x', () => ({ custom: true })))).toBe('Confira este campo.');
    expect(controlMessage(new FormControl('ok'))).toBeNull();
    expect(controlMessage(null)).toBeNull();
  });
});

describe('erros do formulário de signals', () => {
  const build = () => {
    const model = signal({ title: '', category: '', skus: [{ sellerSku: 'cam p' }] });
    return runInInjectionContext(TestBed.inject(Injector), () =>
      form(model, path => validateSchemaRules(path.title, titleRules))
    );
  };

  it('aponta cada erro do servidor para o campo e manda o resto para a raiz', () => {
    const product = build();
    const errors = serverFieldErrors(product, invalid, { fields: { categoryId: 'category' } });

    expect(errors.map(error => [error.message, error.fieldTree])).toEqual([
      ['não deve estar em branco', product.title],
      ['não deve ser nulo', product.category],
      [expect.stringContaining('deve corresponder'), product.skus[0].sellerSku],
      ['não deve ser nulo', undefined]
    ]);
    expect(errors.every(error => error.kind === SERVER_ERROR)).toBe(true);
  });

  it('manda o erro sem campo para a raiz e ignora o erro sem mensagem', () => {
    const product = build();
    const problem: Problem = { status: 400, errors: [{ field: 'title' }, { message: 'Confira os dados.' }] };

    expect(serverFieldErrors(product, problem)).toEqual([{ kind: SERVER_ERROR, message: 'Confira os dados.' }]);
  });

  it('confere os limites do contrato com a mensagem pronta', () => {
    const product = build();
    expect(product.title().errors()).toEqual([expect.objectContaining({ kind: SCHEMA_ERROR, message: 'Preencha este campo.' })]);

    product.title().value.set('Camiseta Basic');
    expect(product.title().errors().map(error => error.message)).toEqual(['Use no máximo 10 caracteres.']);

    product.title().value.set('Camiseta');
    expect(product.title().errors()).toEqual([]);
  });
});
