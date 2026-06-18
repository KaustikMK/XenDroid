//==- DIxendroidmLineNumbers.h - DIA Line Number Enumerator impl -----*- C++ -*-==//
//
//                     The LLVM Compiler Infrastructure
//
// This file is distributed under the University of Illinois Open Source
// License. See LICENSE.TXT for details.
//
//===----------------------------------------------------------------------===//

#ifndef LLVM_DEBUGINFO_PDB_DIA_DIxendroidMLINENUMBERS_H
#define LLVM_DEBUGINFO_PDB_DIA_DIxendroidMLINENUMBERS_H

#include "DIASupport.h"
#include "llvm/DebugInfo/PDB/IPDBEnumChildren.h"

namespace llvm {

class IPDBLineNumber;

class DIxendroidmLineNumbers : public IPDBEnumChildren<IPDBLineNumber> {
public:
  explicit DIxendroidmLineNumbers(CComPtr<IDixendroidmLineNumbers> Dixendroidmerator);

  uint32_t getChildCount() const override;
  ChildTypePtr getChildAtIndex(uint32_t Index) const override;
  ChildTypePtr getNext() override;
  void reset() override;
  DIxendroidmLineNumbers *clone() const override;

private:
  CComPtr<IDixendroidmLineNumbers> Enumerator;
};
}

#endif
