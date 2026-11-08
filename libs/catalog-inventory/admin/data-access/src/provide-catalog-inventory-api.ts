import type { EnvironmentProviders } from '@angular/core';

import { client } from './generated/client.gen';
import { provideHeyApiClient } from './generated/client/client.gen';

/** Liga o cliente gerado do catálogo ao `HttpClient` do app, para as chamadas passarem pelos interceptors. */
export function provideCatalogInventoryApi(): EnvironmentProviders {
  return provideHeyApiClient(client);
}
