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
    path: 'card',
    loadComponent: () => import('./pages/card').then((m) => m.Card),
    title: 'Cardholder app · Kestrel Bank · cardrail',
  },
  {
    path: 'dashboard',
    loadComponent: () => import('./pages/dashboard').then((m) => m.Dashboard),
    title: 'Shop dashboard · cardrail',
  },
  {
    path: 'books',
    loadComponent: () => import('./pages/books').then((m) => m.Books),
    title: 'Books · cardrail',
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
    path: 'how-it-works',
    loadComponent: () => import('./pages/how-it-works').then((m) => m.HowItWorks),
    title: 'How card payments work · cardrail',
  },
  {
    path: '**',
    redirectTo: '',
  },
];
