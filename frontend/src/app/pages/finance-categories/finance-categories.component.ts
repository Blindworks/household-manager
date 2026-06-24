import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import { Category, CategoryKind, CategoryRequest } from '../../models/finance.model';

@Component({
  selector: 'app-finance-categories',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './finance-categories.component.html',
  styleUrl: './finance-categories.component.scss'
})
export class FinanceCategoriesComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  categories: Category[] = [];
  readonly kinds: CategoryKind[] = ['EXPENSE', 'INCOME', 'TRANSFER'];
  form: CategoryRequest = { name: '', kind: 'EXPENSE', color: '#90a4ae' };
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.financeService.getCategories().subscribe({
      next: (c) => this.categories = c,
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  create(): void {
    if (!this.form.name) {
      return;
    }
    this.financeService.createCategory(this.form).subscribe({
      next: () => { this.form = { name: '', kind: 'EXPENSE', color: '#90a4ae' }; this.load(); },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  remove(category: Category): void {
    this.financeService.deleteCategory(category.id).subscribe({
      next: () => this.load(),
      error: (e: Error) => this.errorMessage = e.message
    });
  }
}
