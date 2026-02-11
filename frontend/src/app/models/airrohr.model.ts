export interface AirrohrReading {
  readingTime: string;
  softwareVersion?: string | null;
  ageSeconds?: number | null;
  sdsP1?: number | null;
  sdsP2?: number | null;
}

export interface AirrohrHistoryReading {
  id?: number;
  readingTime: Date;
  softwareVersion?: string | null;
  ageSeconds?: number | null;
  sdsP1?: number | null;
  sdsP2?: number | null;
  createdAt?: Date;
  updatedAt?: Date;
}
