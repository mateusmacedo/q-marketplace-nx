import type { Signal } from '@angular/core';

/** O que uma rota, um item de menu ou um botão exige; os campos presentes valem juntos, e sem nenhum vale para todos. */
export interface AccessRequirement {
  /** Capacidade de negócio, como o servidor a nomeia (`MANAGE_TAXONOMY`). */
  readonly capability?: string;
  /** Permissão composta que o servidor já resolveu (`readTenantCatalog`). */
  readonly permission?: string;
}

/** Capacidades e permissões compostas do usuário, como o servidor responde. */
export interface SessionGrants {
  /** Lista aberta: a capacidade que o painel não conhece não libera nada. */
  readonly capabilities: readonly string[];
  readonly permissions: Readonly<Record<string, boolean | undefined>>;
}

export const NO_GRANTS: SessionGrants = { capabilities: [], permissions: {} };

/** Indica se as concessões atendem à exigência; exigência ausente ou vazia vale para qualquer usuário. */
export function allowsAccess(grants: SessionGrants, requirement: AccessRequirement | null | undefined): boolean {
  if (requirement?.capability !== undefined && !grants.capabilities.includes(requirement.capability)) {
    return false;
  }
  return requirement?.permission === undefined || grants.permissions[requirement.permission] === true;
}

/**
 * Quem está usando o painel, como o servidor responde: capacidades, permissões compostas, loja e tenant. O app
 * registra a implementação, e as features injetam esta classe sem depender do app nem da API. O painel não lê papéis
 * nem claims do token: a política de acesso tem um dono só, o servidor.
 */
export abstract class AdminSession {
  abstract readonly grants: Signal<SessionGrants>;
  /** Loja do lojista; ausente para quem não tem loja. */
  abstract readonly storeId: Signal<string | undefined>;
  abstract readonly tenantId: Signal<string | undefined>;

  allows(requirement?: AccessRequirement | null): boolean {
    return allowsAccess(this.grants(), requirement);
  }

  hasCapability(capability: string): boolean {
    return this.allows({ capability });
  }

  can(permission: string): boolean {
    return this.allows({ permission });
  }
}
