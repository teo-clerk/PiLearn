import { NgModule } from '@angular/core';
import { RouterModule, type Routes } from '@angular/router';
import { LinkComponent } from './components/link/link.component';
import { ImportWorkComponent } from './components/import-work/import-work.component';
import { ImportHomeComponent } from './components/import-home/import-home.component';

export const importRouteList: Routes = [
  {
    // The default. Its absence is why /import rendered an empty outlet under the
    // shell — the reported "black screen with just the word Import".
    path: '',
    component: ImportHomeComponent
  },
  {
    path: 'link',
    component: LinkComponent
  },
  {
    path: 'import-work/:mbid',
    component: ImportWorkComponent
  }
];

@NgModule({
  imports: [RouterModule.forChild(importRouteList)],
  exports: [RouterModule]
})
export class ImportRoutingModule { }
