import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/home').then((m) => m.Home),
    title: 'cardrail · full card payments demo',
  },
  {
    path: 'shop',
    loadComponent: () => import('./pages/shop').then((m) => m.Shop),
    title: 'Demo shop · cardrail',
  },
  {
    path: 'test-cards',
    loadComponent: () => import('./pages/test-cards').then((m) => m.TestCards),
    title: 'Test cards · cardrail',
  },
  {
    path: 'trace/:reference',
    loadComponent: () => import('./pages/trace').then((m) => m.Trace),
    title: 'Payment trace · cardrail',
  },
  {
    path: 'traces',
    loadComponent: () => import('./pages/traces').then((m) => m.Traces),
    title: 'Recent traces · cardrail',
  },
  {
    path: '**',
    redirectTo: '',
  },
];
